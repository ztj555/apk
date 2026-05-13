/**
 * AutoDial PhoneConnectionManager
 * 统一管理手机设备连接（LAN + Cloud），提供设备注册、消息路由、心跳检测
 * 预留文件上传协议处理桩方法
 */

const crypto = require('crypto');
const path = require('path');
const fs = require('fs');

const PhoneConnectionManager = {
    // --- State ---
    phones: new Map(),       // uuid -> { ws, cloudWs, ip, name, note, connectedAt, isCloud, cloudDeviceId, lastHeartbeat }
    activePhoneId: null,     // UUID of active phone
    MAX_PHONES: 5,           // 最大连接设备数
    HEARTBEAT_TIMEOUT: 90000, // 90 秒心跳超时（与云中转一致）

    // Bug2修复: ACK 确认机制
    ACK_TIMEOUT: 3000,          // 3 秒超时等待 ACK
    ACK_RETRY_ON_ALT_CHANNEL: true, // 超时后在备选通道重试一次
    _pendingAcks: new Map(),    // messageId -> { uuid, msg, resolve, reject, timer, channel, retried }
    _msgIdCounter: 0,

    // --- Upload stub state ---
    // _activeUploads: new Map(), // uploadId -> { fileName, chunks, received, filePath }

    /**
     * 生成稳定的设备 UUID
     * LAN: md5(deviceName:ip)
     * Cloud: md5(cloud:deviceName)
     */
    generateUUID(name, ip, isCloud) {
        const base = isCloud ? ('cloud:' + name) : (name + ':' + ip);
        return crypto.createHash('md5').update(base).digest('hex').slice(0, 12);
    },

    /**
     * 注册手机设备（LAN 或 Cloud）
     * @param {string} uuid - 设备唯一标识
     * @param {Object} info - { ip, name, note, ws?, cloudWs?, cloudDeviceId?, isCloud }
     * @returns {boolean} 是否注册成功
     */
    registerPhone(uuid, info) {
        // 超过最大连接数且为新设备，拒绝
        if (this.phones.size >= this.MAX_PHONES && !this.phones.has(uuid)) {
            console.log('[PhoneMgr] Max phone limit reached (' + this.MAX_PHONES + '), rejecting ' + (info.name || uuid));
            return false;
        }

        // 已存在：合并连接信息
        if (this.phones.has(uuid)) {
            const existing = this.phones.get(uuid);
            if (info.ws) existing.ws = info.ws;
            if (info.cloudWs) existing.cloudWs = info.cloudWs;
            if (info.cloudDeviceId) existing.cloudDeviceId = info.cloudDeviceId;
            if (info.isCloud) existing.isCloud = true;
            existing.lastHeartbeat = Date.now();
            console.log('[PhoneMgr] Updated phone: ' + (info.name || uuid) + ' (uuid=' + uuid + ')');
            this._notifyUpdate();
            return true;
        }

        // 新设备
        this.phones.set(uuid, {
            ws: info.ws || null,
            cloudWs: info.cloudWs || null,
            ip: info.ip || (info.isCloud ? 'cloud' : ''),
            name: info.name || ('Phone-' + uuid.slice(0, 6)),
            note: info.note || '',
            connectedAt: Date.now(),
            isCloud: info.isCloud || false,
            cloudDeviceId: info.cloudDeviceId || null,
            lastHeartbeat: Date.now()
        });

        // 自动选为活跃设备
        if (!this.activePhoneId || !this.phones.has(this.activePhoneId)) {
            this.activePhoneId = uuid;
            console.log('[PhoneMgr] Auto-selected active phone: ' + (info.name || uuid));
        }

        console.log('[PhoneMgr] Registered phone: ' + (info.name || uuid) +
            ' (uuid=' + uuid + ', ip=' + (info.ip || 'cloud') +
            ', lan=' + !!info.ws + ', cloud=' + !!info.cloudWs + ')' +
            ', total=' + this.phones.size);

        this._notifyUpdate();
        return true;
    },

    /**
     * 移除手机设备或移除某个传输通道
     * @param {string} uuid
     * @param {string} transport - 'lan' | 'cloud' | 'all'
     */
    removePhone(uuid, transport) {
        const dev = this.phones.get(uuid);
        if (!dev) return;

        if (transport === 'lan') {
            dev.ws = null;
            if (dev.isCloud && dev.cloudWs) {
                console.log('[PhoneMgr] Removed LAN transport: ' + dev.name + ' (uuid=' + uuid + ')');
            } else {
                this.phones.delete(uuid);
                if (this.activePhoneId === uuid) this._selectFirst();
                console.log('[PhoneMgr] Removed phone: ' + dev.name + ' (uuid=' + uuid + ')');
            }
        } else if (transport === 'cloud') {
            dev.cloudWs = null;
            dev.cloudDeviceId = null;
            dev.isCloud = false;
            if (dev.ws) {
                console.log('[PhoneMgr] Removed cloud transport: ' + dev.name + ' (uuid=' + uuid + ')');
            } else {
                this.phones.delete(uuid);
                if (this.activePhoneId === uuid) this._selectFirst();
                console.log('[PhoneMgr] Removed phone: ' + dev.name + ' (uuid=' + uuid + ')');
            }
        } else {
            this.phones.delete(uuid);
            if (this.activePhoneId === uuid) this._selectFirst();
            console.log('[PhoneMgr] Removed phone entirely: ' + (dev.name || uuid) + ' (uuid=' + uuid + ')');
        }

        this._notifyUpdate();
    },

    /**
     * 向手机发送消息，自动选择 LAN（优先）或 Cloud
     * @param {string} uuid
     * @param {Object} msg - JSON 消息对象
     * @returns {boolean} 是否发送成功
     */
    sendToPhone(uuid, msg) {
        const dev = this.phones.get(uuid);
        if (!dev) return false;

        // LAN 优先
        if (dev.ws && dev.ws.readyState === 1) { // WebSocket.OPEN = 1
            try {
                dev.ws.send(JSON.stringify(msg));
                return true;
            } catch (e) {
                console.log('[PhoneMgr] LAN send failed: ' + e.message);
                dev.ws = null;
            }
        }

        // Cloud 降级
        if (dev.cloudWs && dev.cloudWs.readyState === 1 && dev.cloudDeviceId) {
            try {
                const payload = Object.assign({}, msg, { targetDevice: dev.cloudDeviceId });
                dev.cloudWs.send(JSON.stringify(payload));
                return true;
            } catch (e) {
                console.log('[PhoneMgr] Cloud send failed: ' + e.message);
            }
        }

        return false;
    },

    /**
     * Bug2修复: 带ACK确认的发送，用于关键消息(dial/hangup/sms)
     * 在 sendToPhone 基础上增加 messageId，等待手机回 ack，超时则备选通道重试一次
     * @param {string} uuid
     * @param {Object} msg - JSON 消息对象（必须包含 type）
     * @param {number} [timeout=3000] ACK 超时毫秒
     * @returns {Promise<boolean>} 是否确认送达
     */
    sendToPhoneWithAck(uuid, msg, timeout) {
        timeout = timeout || this.ACK_TIMEOUT;
        const dev = this.phones.get(uuid);
        if (!dev) return Promise.resolve(false);

        // 生成 messageId
        const messageId = 'ack_' + Date.now() + '_' + (++this._msgIdCounter);
        const msgWithId = Object.assign({}, msg, { messageId });

        return new Promise((resolve) => {
            const ackEntry = {
                uuid,
                msg: msgWithId,
                resolve,
                timer: null,
                channel: 'unknown',
                retried: false
            };

            // 设置超时
            ackEntry.timer = setTimeout(() => {
                this._pendingAcks.delete(messageId);
                if (!ackEntry.retried && this.ACK_RETRY_ON_ALT_CHANNEL) {
                    // 超时未收到 ACK，在备选通道重试一次
                    console.log('[PhoneMgr] ACK timeout for ' + msg.type + ' (id=' + messageId + '), retrying on alt channel');
                    ackEntry.retried = true;
                    const retryOk = this._sendOnAltChannel(uuid, msgWithId, ackEntry.channel);
                    if (retryOk) {
                        // 再等一个超时周期
                        ackEntry.timer = setTimeout(() => {
                            this._pendingAcks.delete(messageId);
                            console.log('[PhoneMgr] ACK retry timeout for ' + msg.type + ' (id=' + messageId + ')');
                            resolve(false);
                        }, timeout);
                        this._pendingAcks.set(messageId, ackEntry);
                    } else {
                        console.log('[PhoneMgr] ACK retry failed, no alt channel for ' + msg.type);
                        resolve(false);
                    }
                } else {
                    console.log('[PhoneMgr] ACK final timeout for ' + msg.type + ' (id=' + messageId + ')');
                    resolve(false);
                }
            }, timeout);

            this._pendingAcks.set(messageId, ackEntry);

            // 发送消息
            const sent = this._sendWithChannelTracking(uuid, msgWithId, ackEntry);
            if (!sent) {
                clearTimeout(ackEntry.timer);
                this._pendingAcks.delete(messageId);
                resolve(false);
            }
        });
    },

    /**
     * Bug2修复: 内部方法 - 发送消息并记录使用的通道
     */
    _sendWithChannelTracking(uuid, msg, ackEntry) {
        const dev = this.phones.get(uuid);
        if (!dev) return false;

        // LAN 优先
        if (dev.ws && dev.ws.readyState === 1) {
            try {
                dev.ws.send(JSON.stringify(msg));
                ackEntry.channel = 'lan';
                return true;
            } catch (e) {
                console.log('[PhoneMgr] LAN send failed: ' + e.message);
                dev.ws = null;
            }
        }

        // Cloud 降级
        if (dev.cloudWs && dev.cloudWs.readyState === 1 && dev.cloudDeviceId) {
            try {
                const payload = Object.assign({}, msg, { targetDevice: dev.cloudDeviceId });
                dev.cloudWs.send(JSON.stringify(payload));
                ackEntry.channel = 'cloud';
                return true;
            } catch (e) {
                console.log('[PhoneMgr] Cloud send failed: ' + e.message);
            }
        }

        return false;
    },

    /**
     * Bug2修复: 内部方法 - 在备选通道发送（重试时使用）
     */
    _sendOnAltChannel(uuid, msg, usedChannel) {
        const dev = this.phones.get(uuid);
        if (!dev) return false;

        if (usedChannel === 'lan') {
            // 已经用了 LAN，重试用 Cloud
            if (dev.cloudWs && dev.cloudWs.readyState === 1 && dev.cloudDeviceId) {
                try {
                    const payload = Object.assign({}, msg, { targetDevice: dev.cloudDeviceId });
                    dev.cloudWs.send(JSON.stringify(payload));
                    return true;
                } catch (e) {
                    console.log('[PhoneMgr] Cloud retry send failed: ' + e.message);
                }
            }
        } else if (usedChannel === 'cloud') {
            // 已经用了 Cloud，重试用 LAN
            if (dev.ws && dev.ws.readyState === 1) {
                try {
                    dev.ws.send(JSON.stringify(msg));
                    return true;
                } catch (e) {
                    console.log('[PhoneMgr] LAN retry send failed: ' + e.message);
                }
            }
        }
        return false;
    },

    /**
     * Bug2修复: 处理手机端回的 ack 消息
     * @param {Object} msg - { type: "ack", messageId: "...", originalType: "dial" }
     */
    handleAck(msg) {
        if (!msg || !msg.messageId) return;
        const entry = this._pendingAcks.get(msg.messageId);
        if (entry) {
            clearTimeout(entry.timer);
            this._pendingAcks.delete(msg.messageId);
            console.log('[PhoneMgr] ACK received for ' + (msg.originalType || 'unknown') +
                ' (id=' + msg.messageId + ', took=' + (Date.now() - parseInt(msg.messageId.split('_')[1])) + 'ms)');
            entry.resolve(true);
        }
    },

    /**
     * 获取当前活跃手机
     * @returns {Object|null} { uuid, ...device }
     */
    getActivePhone() {
        if (!this.activePhoneId) return null;
        const dev = this.phones.get(this.activePhoneId);
        if (!dev) return null;
        const lanOk = dev.ws && dev.ws.readyState === 1;
        const cloudOk = dev.isCloud && dev.cloudWs && dev.cloudWs.readyState === 1;
        if (!lanOk && !cloudOk) return null;
        return { uuid: this.activePhoneId, name: dev.name, ip: dev.ip, note: dev.note };
    },

    /**
     * 获取所有手机列表（供 UI 使用）
     * @returns {Array}
     */
    getPhoneList() {
        const list = [];
        this.phones.forEach((dev, uuid) => {
            const lanOk = dev.ws && dev.ws.readyState === 1;
            const cloudOk = !!dev.isCloud && dev.cloudWs && dev.cloudWs.readyState === 1;
            let connType = 'none';
            if (lanOk && cloudOk) connType = 'lan+cloud';
            else if (lanOk) connType = 'lan';
            else if (cloudOk) connType = 'cloud';
            list.push({
                id: uuid,
                ip: dev.ip,
                name: dev.name,
                note: dev.note,
                active: uuid === this.activePhoneId,
                connectedAt: dev.connectedAt,
                isCloud: cloudOk,
                connectionType: connType
            });
        });
        return list;
    },

    /**
     * 设置活跃手机
     */
    setActivePhone(uuid) {
        if (!this.phones.has(uuid)) return;
        this.activePhoneId = uuid;
        console.log('[PhoneMgr] Active phone set to: ' + this.phones.get(uuid).name + ' (uuid=' + uuid + ')');
        this._notifyUpdate();
    },

    /**
     * 检查心跳超时，定期调用
     */
    checkHeartbeats() {
        const now = Date.now();
        const toRemove = [];
        this.phones.forEach((dev, uuid) => {
            if (now - dev.lastHeartbeat > this.HEARTBEAT_TIMEOUT) {
                console.log('[PhoneMgr] Heartbeat timeout: ' + dev.name + ' (uuid=' + uuid + ', elapsed=' + Math.round((now - dev.lastHeartbeat) / 1000) + 's)');
                toRemove.push(uuid);
            }
        });
        toRemove.forEach(uuid => this.removePhone(uuid, 'all'));
    },

    /**
     * 更新设备心跳时间（通过 UUID）
     */
    updateHeartbeat(uuid) {
        const dev = this.phones.get(uuid);
        if (dev) {
            dev.lastHeartbeat = Date.now();
        }
    },

    /**
     * 更新设备心跳时间（通过设备名称，用于云端连接）
     */
    updateHeartbeatByName(deviceName) {
        for (const [uuid, dev] of this.phones) {
            if (dev.name === deviceName) {
                dev.lastHeartbeat = Date.now();
                return;
            }
        }
    },

    // ==================== 上传协议桩方法 ====================

    /**
     * 桩: 处理 file_upload_start 消息
     * 未来实现: 验证参数、创建上传目录、初始化分片接收器
     */
    onFileUploadStart(phoneUuid, msg) {
        console.log('[PhoneMgr] [UPLOAD-STUB] file_upload_start from ' + phoneUuid +
            ': fileName=' + msg.fileName +
            ', fileSize=' + msg.fileSize +
            ', totalChunks=' + msg.totalChunks);
        // TODO: 创建 %APPDATA%/autodial-pc/uploads/YYYY-MM-DD/ 目录
        // TODO: 初始化 _activeUploads[msg.uploadId] 分片接收器
    },

    /**
     * 桩: 处理 file_chunk 消息
     * 未来实现: 解码 Base64、追加到临时文件、发送 file_chunk_ack
     */
    onFileChunk(phoneUuid, msg) {
        console.log('[PhoneMgr] [UPLOAD-STUB] file_chunk from ' + phoneUuid +
            ': uploadId=' + msg.uploadId +
            ', chunkIndex=' + msg.chunkIndex +
            ', dataLength=' + (msg.data ? msg.data.length : 0));
        // TODO: Base64 解码并追加到临时文件
        // TODO: 发送 file_chunk_ack 给手机
    },

    /**
     * 桩: 处理 file_upload_complete 消息
     * 未来实现: 验证文件哈希、移动到最终目录、通知 UI
     */
    onFileUploadComplete(phoneUuid, msg) {
        console.log('[PhoneMgr] [UPLOAD-STUB] file_upload_complete from ' + phoneUuid +
            ': uploadId=' + msg.uploadId +
            ', fileName=' + msg.fileName +
            ', fileHash=' + msg.fileHash);
        // TODO: 验证完整文件哈希
        // TODO: 移动临时文件到最终路径
        // TODO: 通过 IPC 通知渲染进程
    },

    /**
     * 桩: 处理 file_upload_error 消息
     * 未来实现: 清理临时文件
     */
    onFileUploadError(phoneUuid, msg) {
        console.log('[PhoneMgr] [UPLOAD-STUB] file_upload_error from ' + phoneUuid +
            ': uploadId=' + msg.uploadId +
            ', errorCode=' + msg.errorCode +
            ', message=' + msg.message);
        // TODO: 清理 _activeUploads[msg.uploadId] 临时文件
    },

    // ==================== Internal ====================

    _selectFirst() {
        const first = this.phones.keys().next();
        this.activePhoneId = first.done ? null : first.value;
        if (this.activePhoneId) {
            console.log('[PhoneMgr] Auto-selected first phone: ' + this.phones.get(this.activePhoneId).name);
        }
    },

    _notifyUpdate() {
        if (typeof global._notifyPhonesUpdate === 'function') {
            global._notifyPhonesUpdate();
        }
    }
};

module.exports = PhoneConnectionManager;
