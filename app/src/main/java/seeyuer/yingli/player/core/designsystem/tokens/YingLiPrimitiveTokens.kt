package seeyuer.yingli.player.core.designsystem.tokens

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
data class ColorScale11(
    val tone50: Color,
    val tone100: Color,
    val tone200: Color,
    val tone300: Color,
    val tone400: Color,
    val tone500: Color,
    val tone600: Color,
    val tone700: Color,
    val tone800: Color,
    val tone900: Color,
    val tone950: Color,
) {
    val values: List<Color> = listOf(
        tone50,
        tone100,
        tone200,
        tone300,
        tone400,
        tone500,
        tone600,
        tone700,
        tone800,
        tone900,
        tone950,
    )
}

object YingLiPrimitiveTokens {
    val Neutral0 = Color(0xFFFFFFFF)
    val Neutral50 = Color(0xFFFAFAFA)
    val Neutral100 = Color(0xFFF5F5F5)
    val Neutral200 = Color(0xFFEEEEEE)
    val Neutral300 = Color(0xFFE0E0E0)
    val Neutral400 = Color(0xFFC7C7C7)
    val Neutral500 = Color(0xFFA3A3A3)
    val Neutral600 = Color(0xFF737373)
    val Neutral700 = Color(0xFF525252)
    val Neutral800 = Color(0xFF333333)
    val Neutral900 = Color(0xFF1F1F1F)
    val Neutral950 = Color(0xFF121212)
    val Neutral1000 = Color(0xFF000000)

    val neutralScale: List<Color> = listOf(
        Neutral0,
        Neutral50,
        Neutral100,
        Neutral200,
        Neutral300,
        Neutral400,
        Neutral500,
        Neutral600,
        Neutral700,
        Neutral800,
        Neutral900,
        Neutral950,
        Neutral1000,
    )

    val SteelBlueA = ColorScale11(
        tone50 = Color(0xFFF2F7FA),
        tone100 = Color(0xFFE2EEF4),
        tone200 = Color(0xFFC1DCE8),
        tone300 = Color(0xFF98C3D5),
        tone400 = Color(0xFF6FA8C0),
        tone500 = Color(0xFF4E8AA5),
        tone600 = Color(0xFF3B6F88),
        tone700 = Color(0xFF315A70),
        tone800 = Color(0xFF2B4C5D),
        tone900 = Color(0xFF273F4C),
        tone950 = Color(0xFF172832),
    )
    val RedA = ColorScale11(
        tone50 = Color(0xFFFFF5F4),
        tone100 = Color(0xFFFEE8E6),
        tone200 = Color(0xFFFBCFCB),
        tone300 = Color(0xFFF5AAA3),
        tone400 = Color(0xFFEA7D73),
        tone500 = Color(0xFFD85D52),
        tone600 = Color(0xFFB9443A),
        tone700 = Color(0xFF96372F),
        tone800 = Color(0xFF7C322C),
        tone900 = Color(0xFF682F2B),
        tone950 = Color(0xFF391512),
    )
    val AmberA = ColorScale11(
        tone50 = Color(0xFFFFF9EB),
        tone100 = Color(0xFFFFF0C6),
        tone200 = Color(0xFFFCDD88),
        tone300 = Color(0xFFF7C44D),
        tone400 = Color(0xFFEAA923),
        tone500 = Color(0xFFC98A12),
        tone600 = Color(0xFFA66A0D),
        tone700 = Color(0xFF85500F),
        tone800 = Color(0xFF6E4013),
        tone900 = Color(0xFF5D3615),
        tone950 = Color(0xFF351B07),
    )
    val GreenA = ColorScale11(
        tone50 = Color(0xFFF1F8F4),
        tone100 = Color(0xFFDDEFE4),
        tone200 = Color(0xFFBCE0CA),
        tone300 = Color(0xFF8FC9A5),
        tone400 = Color(0xFF64AE82),
        tone500 = Color(0xFF438F65),
        tone600 = Color(0xFF347451),
        tone700 = Color(0xFF2D5D43),
        tone800 = Color(0xFF284B38),
        tone900 = Color(0xFF233E30),
        tone950 = Color(0xFF102219),
    )

    val SteelBlueB = ColorScale11(
        tone50 = Color(0xFFF4F9FC),
        tone100 = Color(0xFFE5F1F7),
        tone200 = Color(0xFFC6E2EE),
        tone300 = Color(0xFFA1CCDE),
        tone400 = Color(0xFF7AB3CB),
        tone500 = Color(0xFF5A96B2),
        tone600 = Color(0xFF477B94),
        tone700 = Color(0xFF3B647A),
        tone800 = Color(0xFF325364),
        tone900 = Color(0xFF2B4351),
        tone950 = Color(0xFF192A35),
    )
    val RedB = ColorScale11(
        tone50 = Color(0xFFFFF8F7),
        tone100 = Color(0xFFFFECEA),
        tone200 = Color(0xFFFFD5D1),
        tone300 = Color(0xFFFEB3AB),
        tone400 = Color(0xFFF6887E),
        tone500 = Color(0xFFE66A5E),
        tone600 = Color(0xFFC75146),
        tone700 = Color(0xFFA14138),
        tone800 = Color(0xFF843933),
        tone900 = Color(0xFF6D342F),
        tone950 = Color(0xFF3C1714),
    )
    val AmberB = ColorScale11(
        tone50 = Color(0xFFFFFAED),
        tone100 = Color(0xFFFFF4D3),
        tone200 = Color(0xFFFFE397),
        tone300 = Color(0xFFFFCD5C),
        tone400 = Color(0xFFF6B435),
        tone500 = Color(0xFFD69629),
        tone600 = Color(0xFFB37622),
        tone700 = Color(0xFF905A1D),
        tone800 = Color(0xFF75471B),
        tone900 = Color(0xFF623A1A),
        tone950 = Color(0xFF381D09),
    )
    val GreenB = ColorScale11(
        tone50 = Color(0xFFF3FAF6),
        tone100 = Color(0xFFE0F2E7),
        tone200 = Color(0xFFC1E6CF),
        tone300 = Color(0xFF98D2AE),
        tone400 = Color(0xFF6FB98D),
        tone500 = Color(0xFF509B71),
        tone600 = Color(0xFF40805C),
        tone700 = Color(0xFF37674C),
        tone800 = Color(0xFF2F523E),
        tone900 = Color(0xFF274234),
        tone950 = Color(0xFF12241B),
    )
}
