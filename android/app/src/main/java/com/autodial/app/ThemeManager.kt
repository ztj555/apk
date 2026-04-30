package com.autodial.app

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * AutoDial 手机端 - 主题引擎
 * 参考 PC 端 theme-data.js，16 套主题 × 7 级亮度
 */

data class ThemeColors(
    val gold: String,
    val goldLight: String,
    val goldDark: String,
    val bg: String,
    val bg2: String,
    val bg3: String,
    val text: String,
    val text2: String,
    val green: String,
    val red: String
)

data class ThemeInfo(
    val id: String,
    val name: String,
    val nameEn: String,
    val category: String,
    val keywords: List<String>,
    val defaultMode: String,
    val colors: Map<String, ThemeColors>
)

object ThemeManager {

    private const val PREF_NAME = "autodial_theme"
    private const val KEY_THEME_ID = "theme_id"
    private const val KEY_MODE = "theme_mode"

    val DEFAULT_THEME_ID = "dark-gold"
    val DEFAULT_MODE = "dark"

    // 主题变更监听器
    private val listeners = mutableListOf<() -> Unit>()

    fun addOnThemeChangedListener(listener: () -> Unit) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeOnThemeChangedListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /**
     * 通知所有注册的监听器主题已变更
     */
    private fun notifyThemeChanged() {
        listeners.toList().forEach { listener ->
            try { listener() } catch (_: Exception) {}
        }
    }

    data class ModeInfo(val key: String, val name: String, val icon: String)

    val MODES: List<ModeInfo> = listOf(
        ModeInfo("dark", "暗夜", "\uD83C\uDF11"),
        ModeInfo("dusk", "黄昏", "\uD83C\uDF06"),
        ModeInfo("dawn", "朝阳", "\uD83C\uDF05"),
        ModeInfo("twilight", "暮光", "\uD83C\uDF07"),
        ModeInfo("warm", "暖光", "\uD83C\uDF24"),
        ModeInfo("mist", "晨雾", "\uD83C\uDF25"),
        ModeInfo("light", "亮白", "\u2600\uFE0F")
    )

    // ==================== 16 套主题数据 ====================
    val THEMES: List<ThemeInfo> = buildThemes()

    private fun buildThemes(): List<ThemeInfo> = listOf(
        // 1. 暗金
        ti("dark-gold","暗金","Dark Gold","tech",listOf("高贵","经典","质感"),"dark",
            c("dark","#C9A84C","#F0C040","#8B6914","#111318","#1A1D24","#22262F","#E8DCC8","#A09070","#2ECC71","#E74C3C"),
            c("dusk","#C4A04A","#E8C040","#907020","#1E1A16","#2D2820","#3A3428","#EDE0CC","#B09A70","#5ABF6B","#E06050"),
            c("dawn","#B89040","#D8B838","#886828","#2C2620","#3E3630","#504840","#F0E4D0","#C0A880","#60C870","#E06050"),
            c("twilight","#b88f38","#dab630","#866625","#4b453f","#5b544f","#686058","#F0E4D0","#b29c77","#57c46e","#db5a4a"),
            c("warm","#564012","#826a2c","#81621d","#938e87","#9f9b98","#a09a90","#292620","#4a4032","#44bb68","#d04d3e"),
            c("mist","#8f6a11","#ab8628","#7d5e16","#d1ccc4","#d8d7d6","#d0cac0","#403729","#857459","#32b363","#c64132"),
            c("light","#B8860B","#E6A800","#7A5C12","#FAF6ED","#FFFFFF","#F0EBE0","#2C2416","#7A6B52","#27AE60","#C0392B")
        ),
        // 2. 冰蓝冷峻
        ti("cyber-frost","冰蓝冷峻","Cyber Frost","tech",listOf("冷峻","科技感","专业"),"dark",
            c("dark","#00BCD4","#4DD0E1","#006064","#0A1628","#122A45","#1A3A5C","#E0F0FF","#7BA3C4","#00E676","#FF5252"),
            c("dusk","#0EA5BC","#40C8D8","#006570","#141E30","#1E3348","#2A4A60","#D8ECF8","#88B0CC","#10D060","#F04848"),
            c("dawn","#0C98B0","#30B8D0","#005868","#1C2C40","#284060","#345878","#E0F0FA","#90C0D8","#18D468","#F05050"),
            c("twilight","#0a98af","#29b6ce","#005e6e","#3b4a5c","#485d78","#4b6e8b","#E0F0FA","#85b5d0","#14d265","#ec4b4b"),
            c("warm","#044750","#216f7a","#006e7c","#82909e","#94a0b0","#82a0b7","#202931","#304450","#0cce5e","#e24040"),
            c("mist","#027583","#1d8e9c","#007a87","#bfccd6","#d4d9df","#b1cbdc","#2e4c6c","#6292b5","#05ca57","#d93636"),
            c("light","#0097A7","#00ACC1","#00838F","#E8F4FC","#FFFFFF","#D0E8F5","#1A3A5C","#5A8AAF","#00C853","#D32F2F")
        ),
        // 3. 极简白
        ti("minimalist","极简白","Minimalist","comfort",listOf("极简","干净","护眼"),"light",
            c("dark","#888888","#AAAAAA","#666666","#1A1A1A","#2A2A2A","#3A3A3A","#E8E8E8","#999999","#4CAF50","#EF5350"),
            c("dusk","#777777","#999999","#606060","#222222","#333333","#444444","#E0E0E0","#909090","#50B855","#F04848"),
            c("dawn","#686868","#888888","#555555","#383838","#4A4A4A","#5C5C5C","#EAEAEA","#A0A0A0","#55C060","#EF5050"),
            c("twilight","#656565","#858585","#525252","#565656","#646464","#727272","#EAEAEA","#9b9b9b","#52bb5c","#ee4d4c"),
            c("warm","#4a4a4a","#676767","#4d4d4d","#9c9c9c","#a2a2a2","#a6a6a6","#333333","#4c4c4c","#4cb054","#ea4543"),
            c("mist","#595959","#707070","#474747","#d7d7d7","#d7d7d7","#d2d2d2","#2f2f2f","#8c8c8c","#47a64c","#e73e3a"),
            c("light","#555555","#777777","#444444","#FFFFFF","#FAFAFA","#F0F0F0","#1A1A1A","#888888","#43A047","#E53935")
        ),
        // 4. 毛玻璃
        ti("glassmorphism","毛玻璃","Glassmorphism","tech",listOf("玻璃","半透明","现代感"),"dark",
            c("dark","#A78BFA","#C4B5FD","#7C3AED","#0F0F19","#1E1E32","#2D2D46","#F0EEFF","#A099CC","#34D399","#F87171"),
            c("dusk","#9878E8","#B8A0F0","#6830D0","#16121F","#232337","#30304B","#E8E4F8","#988ABB","#30C890","#F06060"),
            c("dawn","#9070E0","#B090F0","#6028C8","#201C30","#282841","#373750","#EAE6F8","#A898C8","#28C888","#F06868"),
            c("twilight","#8f6de3","#af8ff2","#6228cb","#3e394e","#4e4e6a","#5d5d82","#EAE6F8","#9c8dbd","#24c687","#f06363"),
            c("warm","#3a2a60","#64538e","#6728d1","#847e94","#9595a8","#8e8eb0","#19171d","#383346","#1cc185","#f05656"),
            c("mist","#6c4abb","#8769ce","#6a28d6","#c0b9d0","#d4d4dc","#c2c1d4","#403952","#746998","#15bc82","#ef4b4b"),
            c("light","#8B5CF6","#A78BFA","#6D28D9","#E8E0F8","#FFFFFF","#F0F0FA","#2D2640","#6B6190","#10B981","#EF4444")
        ),
        // 5. 活力橙
        ti("energetic-orange","活力橙","Energetic Orange","creative",listOf("活泼","温暖","有活力"),"dark",
            c("dark","#FF9800","#FFB74D","#E65100","#1A1510","#2A2018","#3A2D20","#FFF5E6","#B08D60","#66BB6A","#EF5350"),
            c("dusk","#F08C00","#FFA830","#D05800","#1E1812","#302418","#403020","#F8F0E0","#B89868","#5CB060","#E84848"),
            c("dawn","#E08000","#FF9820","#C04800","#2E2218","#403420","#524628","#FAF0E0","#C8A870","#60B864","#F05050"),
            c("twilight","#e37f00","#ff9a21","#c74d00","#4d4238","#5d5241","#6c5f42","#FAF0E0","#c09e6a","#5db761","#ee4d4c"),
            c("warm","#603400","#905e21","#d85a00","#978d84","#a09a90","#a99b7f","#2b2620","#50402a","#56b45a","#eb4543"),
            c("mist","#a55500","#c07621","#e66500","#d5cdc5","#d9d6d2","#dccdb2","#503f2e","#a67f55","#50b154","#e73e3a"),
            c("light","#F57C00","#FFA726","#EF6C00","#FFF8F0","#FFFFFF","#FFEFD5","#3D2B1A","#A07850","#4CAF50","#E53935")
        ),
        // 6. 圆润糖果
        ti("rounded-candy","圆润糖果","Rounded Candy","comfort",listOf("圆润","可爱","柔和"),"light",
            c("dark","#FF6B9D","#FF8FB1","#C2185B","#1A1020","#2A1830","#3A2040","#FFE8F0","#A07090","#69F0AE","#FF8A80"),
            c("dusk","#F06898","#FF88B0","#C01058","#201520","#302038","#402848","#FFE0E8","#A87898","#60E8A8","#FF8080"),
            c("dawn","#E86090","#FF80A8","#B80848","#2E1E28","#402838","#523448","#FFE8F0","#B88898","#58E8A0","#FF7878"),
            c("twilight","#e95b8d","#fd7ca5","#bd0b4c","#4d3e47","#5d4856","#6c4e61","#FFE8F0","#b38093","#4be89a","#ff7272"),
            c("warm","#6c253e","#974a64","#c81254","#97878f","#a0949c","#a98c9a","#2c2226","#533844","#2ce78b","#ff6565"),
            c("mist","#b63762","#ca557c","#d2175b","#d5c6cc","#d9d4d7","#dcc1cb","#503241","#a46684","#12e67e","#ff5a5a"),
            c("light","#EC407A","#F06292","#D81B60","#FFF0F5","#FFFFFF","#FFE4EC","#3D1E2D","#A06080","#00E676","#FF5252")
        ),
        // 7. 深空紫
        ti("deep-space","深空紫","Deep Space","tech",listOf("深邃","神秘","高端"),"dark",
            c("dark","#BB86FC","#DA98FF","#7B1FA2","#0D0A18","#18142E","#241E42","#E8DEFF","#9575CD","#00E676","#FF5252"),
            c("dusk","#B078F0","#D090F8","#7018A0","#12101C","#1E1836","#2A2448","#E0D8F8","#9070C0","#10D868","#F04848"),
            c("dawn","#A868E8","#C888F0","#681098","#1C1628","#2A2240","#383058","#E8E0FA","#A088C8","#18E070","#F45050"),
            c("twilight","#a65ee0","#c47ee8","#6e139b","#3d3748","#4a435d","#534b70","#E8E0FA","#997ec7","#14dc6c","#ef4b4b"),
            c("warm","#56256b","#7b488f","#7b1aa1","#898394","#9591a0","#938ba8","#1f1c25","#44345f","#0cd462","#e44040"),
            c("mist","#9e34bb","#ac4fc4","#8620a6","#cac4d4","#d4d3d9","#c9c1d8","#403154","#835ec3","#05cd59","#da3636"),
            c("light","#9C27B0","#AB47BC","#8E24AA","#F5F0FF","#FFFFFF","#EDE5F8","#2D1E42","#7E57C2","#00C853","#D32F2F")
        ),
        // 8. 森林绿
        ti("forest-green","森林绿","Forest Green","comfort",listOf("自然","安静","护眼"),"dark",
            c("dark","#81C784","#A5D6A7","#388E3C","#0E1810","#182818","#223822","#E0F0E0","#7AA07A","#69F0AE","#FF8A80"),
            c("dusk","#70B870","#98CC98","#308030","#141E14","#203020","#2C402C","#D8EAD8","#78A078","#60E8A0","#F07070"),
            c("dawn","#68A868","#88C088","#287028","#1C2A1C","#2C3C2C","#3A503A","#E4F0E4","#88B088","#60E0A0","#F06868"),
            c("twilight","#64a964","#83bf84","#2c772d","#3c493c","#4c594c","#546954","#E4F0E4","#80a880","#52e19a","#ee6160"),
            c("warm","#254625","#4b714c","#368838","#869186","#969e96","#91a291","#222922","#334533","#30e38b","#eb514f"),
            c("mist","#37773a","#569259","#3e9641","#c6cfc6","#d5d8d5","#c5d3c5","#324c32","#649064","#13e57e","#e7423f"),
            c("light","#4CAF50","#66BB6A","#43A047","#F0F8F0","#FFFFFF","#E8F4E8","#1E3A1E","#5E8A5E","#00E676","#E53935")
        ),
        // 9. 赛博朋克
        ti("cyberpunk","赛博朋克","Cyberpunk Neon","creative",listOf("霓虹","酷炫","未来"),"dark",
            c("dark","#00FFFF","#80FFFF","#008B8B","#0A0010","#150022","#220035","#F0F0FF","#8866CC","#39FF14","#FF0039"),
            c("dusk","#00D8D8","#70ECEC","#007878","#0E0018","#1A0E28","#280038","#E8E0F8","#9070C0","#30F010","#F02038"),
            c("dawn","#00C0C0","#60E0E0","#006868","#180828","#241438","#322048","#F0E8FA","#A888D0","#30F018","#F82840"),
            c("twilight","#00bfc3","#5ddee0","#006f71","#382c48","#453756","#4c4063","#F0E8FA","#9b7ec5","#29ef26","#f92541"),
            c("warm","#003c3f","#2b6f73","#008088","#848194","#928a9c","#898ba4","#1b1a1f","#403456","#18eb47","#fc2042"),
            c("mist","#00717d","#2a939e","#008e9a","#c5cad4","#d3d0d7","#bdcada","#2f2f42","#705da1","#0ae863","#fe1a43"),
            c("light","#00BCD4","#4DD0E1","#0097A7","#F0FAFF","#FFFFFF","#E0F5FF","#1A1A2E","#665599","#00E676","#FF1744")
        ),
        // 10. 暖光米色
        ti("warm-cream","暖光米色","Warm Cream","comfort",listOf("温暖","舒适","复古"),"light",
            c("dark","#D4A574","#E8C49A","#A67C52","#1A1612","#2A2218","#3A2E20","#F0E6D8","#A09080","#81C784","#E57373"),
            c("dusk","#C89860","#E0B880","#987040","#1C1810","#2C2418","#3C3020","#F0E0D0","#A89078","#78B878","#E06060"),
            c("dawn","#B88850","#D8A870","#886038","#2A2018","#3C3028","#4E4038","#F8EAD8","#B8A088","#70C070","#E06868"),
            c("twilight","#ba8a54","#d7a871","#8a623a","#4a4138","#594f48","#675a51","#F8EAD8","#b09a82","#6bbd6b","#e26564"),
            c("warm","#4d3b25","#7a644a","#8f6840","#958d84","#9e9894","#a2978c","#2a2620","#494035","#5eb860","#e85e5c"),
            c("mist","#846345","#a18061","#936d45","#d4cec5","#d8d6d4","#d4cabe","#504332","#96856d","#53b256","#ec5755"),
            c("light","#C4956A","#D4A574","#967048","#FFF9F0","#FFFFFF","#F5EDE0","#3D3020","#908068","#4CAF50","#EF5350")
        ),
        // 11. 海洋蓝
        ti("ocean-blue","海洋蓝","Ocean Blue","comfort",listOf("清新","开阔","平静"),"light",
            c("dark","#42A5F5","#64B5F6","#1565C0","#0B1424","#152238","#1E3050","#E0ECFF","#7890B8","#00E676","#FF5252"),
            c("dusk","#3898E8","#58B0F0","#1058A8","#0E1820","#1A2C40","#243C58","#D8E8F8","#7090B0","#10D860","#F04848"),
            c("dawn","#3088D8","#48A0E8","#084898","#162238","#243850","#324E68","#E0F0FF","#88A8C8","#18D868","#F05050"),
            c("twilight","#2d88da","#47a1ea","#0b4fa1","#374256","#45566a","#4d667e","#E0F0FF","#7f9ebe","#14d665","#ee4d4c"),
            c("warm","#123f67","#346691","#115fb5","#838c9c","#929ca8","#8b9db0","#1f242a","#33404f","#0cd05e","#eb4543"),
            c("mist","#1a6aaf","#3985c4","#166dc6","#c4ccd7","#d3d7dc","#c0ccdb","#29374c","#637f9f","#05cb57","#e73e3a"),
            c("light","#1E88E5","#42A5F5","#1976D2","#F0F6FF","#FFFFFF","#E3ECF8","#152238","#5C7898","#00C853","#E53935")
        ),
        // 12. 蓝绿渐变
        ti("teal-gradient","蓝绿渐变","Teal Gradient","gradient",listOf("清新","渐变","现代"),"light",
            c("dark","#00B7C3","#4DD8E0","#007C85","#0A1A1E","#122A30","#1A3A42","#E0F5F8","#6AAAB8","#26D0A0","#FF6B6B"),
            c("dusk","#00A8B4","#38C8D2","#006E78","#122028","#1C3240","#284458","#D8F0F5","#80B8C8","#20C898","#F06868"),
            c("dawn","#0098A4","#28B8C4","#006070","#1E3040","#2A4458","#386070","#E8F8FC","#90C8D8","#20C8A0","#F07070"),
            c("twilight","#0096a3","#22b5c3","#006272","#3c4e5c","#4a6071","#507684","#E8F8FC","#85bbcb","#1ec69e","#ef6b68"),
            c("warm","#004d55","#1e727c","#006777","#83959d","#95a2ac","#88aab3","#232d30","#334a51","#18c098","#ec5e56"),
            c("mist","#007b8c","#1b91a2","#006a7a","#c0d2d5","#d4dade","#b8d6db","#2b4b53","#6092a2","#13bb93","#e95346"),
            c("light","#00899E","#00A5BD","#006D7D","#E8FAFA","#FFFFFF","#D8F4F6","#163840","#588898","#10B890","#E74C3C")
        ),
        // 13. 薄荷清新
        ti("mint-fresh","薄荷清新","Mint Fresh","gradient",listOf("薄荷","清爽","自然"),"light",
            c("dark","#2ED573","#7BED9F","#1E8449","#0A1A10","#142818","#1E3822","#E0F8E8","#6AAF80","#7BED9F","#FF6B81"),
            c("dusk","#28C068","#60E088","#187840","#122218","#1E3424","#2A4830","#D8F0E0","#70B888","#60E088","#F07080"),
            c("dawn","#22B060","#50D080","#146838","#1C2E22","#284032","#365240","#E4F8EC","#80C898","#58D880","#F07880"),
            c("twilight","#22b262","#4ad280","#136d3a","#3b4d42","#485d51","#4f6b59","#E4F8EC","#78bf8f","#51d980","#f2747c"),
            c("warm","#0e4c2a","#2f7a51","#10793d","#85978b","#94a099","#8ba594","#232d26","#2d4935","#3fdb81","#f66a73"),
            c("mist","#168047","#329f65","#0d8340","#c3d5ca","#d4d9d6","#bdd7c6","#2e4b34","#60a171","#30dd81","#fa626a"),
            c("light","#20BF6B","#26DE81","#0B8A42","#EDFFF4","#FFFFFF","#DFF8E8","#1A3820","#5A9A6A","#26DE81","#FC5C65")
        ),
        // 14. 珊瑚日落
        ti("coral-sunset","珊瑚日落","Coral Sunset","gradient",listOf("珊瑚","温暖","渐变"),"light",
            c("dark","#FF7F50","#FFA07A","#CD5C5C","#1A1410","#2A2018","#3A2C20","#FFF0E8","#B08868","#2ED573","#FF6348"),
            c("dusk","#F07848","#FF9870","#C05040","#1E1612","#302420","#42302C","#FFE8DC","#B89070","#28C868","#F06040"),
            c("dawn","#E87040","#FF8858","#B84838","#282018","#3C3028","#504038","#FFF4EC","#C0A080","#30C870","#F06848"),
            c("twilight","#e86f40","#ff885a","#bd4733","#484038","#594f48","#6a5951","#FFF4EC","#ba9678","#2ec76f","#f06643"),
            c("warm","#5e2c1a","#8e543f","#c84627","#948b83","#9e9894","#a8948b","#2c2521","#4f3c30","#28c46e","#ef6136"),
            c("mist","#b45231","#cb6f4f","#d2441c","#d4cac3","#d8d6d4","#dcc6bc","#50372d","#a5775e","#23c16c","#ee5d2b"),
            c("light","#E86840","#FF8A65","#D84315","#FFF5EE","#FFFFFF","#FFE8DD","#3D2218","#A07058","#20BF6B","#EE5A24")
        ),
        // 15. 薰衣草
        ti("lavender","薰衣草","Lavender","gradient",listOf("薰衣草","优雅","柔和"),"light",
            c("dark","#A29BFE","#C4BFFF","#6C5CE7","#12101E","#1C1A30","#282442","#ECE8FF","#9088CC","#55EFC4","#FF7675"),
            c("dusk","#9688F0","#B8AAFF","#6050D8","#161424","#222040","#302C54","#E8E4FF","#9890CC","#50E8B8","#F07070"),
            c("dawn","#8A80E8","#ACA0FF","#5848D0","#1E1A30","#2A2848","#383860","#F0ECFF","#A8A0D0","#50E8B8","#F07878"),
            c("twilight","#887de7","#a99dff","#5848cf","#3e3a4f","#4a4863","#525277","#F0ECFF","#9f96c6","#4edda7","#ee6f6e"),
            c("warm","#3d376a","#655e97","#5a4acd","#898598","#9594a4","#908ead","#201e25","#3c384b","#4ac480","#eb5957"),
            c("mist","#6358af","#7e74c7","#5a4aca","#c8c5d6","#d4d4da","#c5c2db","#413a53","#8177a7","#46ae5e","#e74642"),
            c("light","#7C6FE0","#9B8FFF","#5B4BC9","#F3F0FF","#FFFFFF","#E8E4FA","#2D2640","#7A70A0","#43A047","#E53935")
        ),
        // 16. 天空蓝
        ti("sky-blue","天空蓝","Sky Blue","gradient",listOf("天空","清爽","明亮"),"light",
            c("dark","#4682E6","#74A5F8","#2563EB","#0C1220","#141E38","#1C2A4C","#E4EEFF","#7098C8","#51CF66","#FF6B6B"),
            c("dusk","#3874D8","#6098F0","#1D56D0","#101828","#1A2840","#243858","#DCE8F8","#7898C0","#48C860","#F06868"),
            c("dawn","#3068D0","#5090E8","#1848B8","#162038","#223454","#30486C","#E8F0FF","#88B0D0","#48C460","#F07070"),
            c("twilight","#2f69ce","#4f90e7","#184ab6","#364056","#43526e","#496182","#E8F0FF","#7ea6c8","#47c35f","#f06969"),
            c("warm","#18376a","#3a5d91","#194fb0","#818a9c","#919aaa","#849ab4","#1d2228","#2e3f4f","#44c25c","#f05757"),
            c("mist","#2c6bc6","#4781d1","#1a53ab","#c0cad7","#d3d6dd","#b6cbdf","#2b3c53","#5f87ae","#42c159","#f04848"),
            c("light","#2B6CC4","#4A90E0","#1A56A8","#EBF4FF","#FFFFFF","#D8ECFC","#162840","#5880A8","#40C057","#F03E3E")
        )
    )

    // 辅助构建函数
    private fun ti(id:String,name:String,nameEn:String,cat:String,kw:List<String>,dm:String,vararg cm:Pair<String,ThemeColors>): ThemeInfo {
        return ThemeInfo(id,name,nameEn,cat,kw,dm,cm.toMap())
    }

    private fun c(mode:String,gold:String,goldLight:String,goldDark:String,bg:String,bg2:String,bg3:String,text:String,text2:String,green:String,red:String): Pair<String,ThemeColors> {
        return mode to ThemeColors(gold,goldLight,goldDark,bg,bg2,bg3,text,text2,green,red)
    }

    // ==================== 存取 ====================

    fun saveTheme(context: Context, themeId: String, mode: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_THEME_ID, themeId)
            .putString(KEY_MODE, mode)
            .apply()
        // 通知所有监听器主题已变更
        notifyThemeChanged()
    }

    fun loadThemeId(context: Context): String {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_THEME_ID, DEFAULT_THEME_ID) ?: DEFAULT_THEME_ID
    }

    fun loadMode(context: Context): String {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MODE, DEFAULT_MODE) ?: DEFAULT_MODE
    }

    fun getThemeById(id: String): ThemeInfo {
        return THEMES.find { it.id == id } ?: THEMES[0]
    }

    fun getColors(context: Context): ThemeColors {
        val theme = getThemeById(loadThemeId(context))
        val mode = loadMode(context)
        return theme.colors[mode] ?: theme.colors["dark"]!!
    }

    // ==================== 颜色应用 ====================

    fun applyToView(view: View, colors: ThemeColors) {
        val tag = view.tag?.toString() ?: ""
        when (tag) {
            "bg" -> view.setBackgroundColor(parseColor(colors.bg))
            "bg2" -> view.setBackgroundColor(parseColor(colors.bg2))
            "bg3" -> view.setBackgroundColor(parseColor(colors.bg3))
            "text" -> if (view is TextView) view.setTextColor(parseColor(colors.text))
            "text2" -> if (view is TextView) view.setTextColor(parseColor(colors.text2))
            "gold" -> if (view is TextView) view.setTextColor(parseColor(colors.gold))
                     else view.setBackgroundColor(parseColor(colors.gold))
            "goldLight" -> if (view is TextView) view.setTextColor(parseColor(colors.goldLight))
            "goldDark" -> view.setBackgroundColor(parseColor(colors.goldDark))
            "green" -> if (view is TextView) view.setTextColor(parseColor(colors.green))
            "red" -> if (view is TextView) view.setTextColor(parseColor(colors.red))
            "goldBtn" -> {
                view.setBackgroundColor(parseColor(colors.gold))
                if (view is ViewGroup) {
                    for (i in 0 until view.childCount) {
                        val child = view.getChildAt(i)
                        if (child is TextView) child.setTextColor(parseColor(colors.bg))
                    }
                }
            }
            "goldBtnText" -> if (view is TextView) {
                view.setTextColor(parseColor(colors.gold))
            }
            "switchOn" -> view.setBackgroundColor(parseColor(colors.gold))
            "divider" -> view.setBackgroundColor(parseColor(colors.bg3))
            "statusBarBg" -> view.setBackgroundColor(parseColor(colors.bg))
        }

        // hint 颜色（EditText 专用）
        if (view is android.widget.EditText) {
            view.setHintTextColor(parseColor(colors.text2))
        }

        // 递归子 View
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyToView(view.getChildAt(i), colors)
            }
        }
    }

    private fun parseColor(colorStr: String): Int {
        return try {
            android.graphics.Color.parseColor(colorStr)
        } catch (_: Exception) {
            android.graphics.Color.GRAY
        }
    }

    // 判断是否为浅色模式
    fun isLightMode(context: Context): Boolean {
        val colors = getColors(context)
        val bg = parseColor(colors.bg)
        val r = android.graphics.Color.red(bg)
        val g = android.graphics.Color.green(bg)
        val b = android.graphics.Color.blue(bg)
        // 计算亮度
        val luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255
        return luminance > 0.5
    }
}
