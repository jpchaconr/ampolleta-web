package cl.jpchacon.ampolleta.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Los pocos íconos de Material Symbols que usa la app y que no vienen en material-icons-core.
 * Se dibujan aquí para no sumar material-icons-extended, que pesa varios MB.
 */
internal object AppIcons {
    val Lightbulb = icon(
        "Lightbulb",
        "M9,21c0,0.55 0.45,1 1,1h4c0.55,0 1,-0.45 1,-1v-1L9,20v1zM12,2C8.14,2 5,5.14 5,9c0,2.38 1.19,4.47 3,5.74L8,17" +
            "c0,0.55 0.45,1 1,1h6c0.55,0 1,-0.45 1,-1v-2.26c1.81,-1.27 3,-3.36 3,-5.74 0,-3.86 -3.14,-7 -7,-7z",
    )
    val Power = icon(
        "Power",
        "M13,3h-2v10h2L13,3zM17.83,5.17l-1.42,1.42C17.99,7.86 19,9.81 19,12c0,3.87 -3.13,7 -7,7s-7,-3.13 -7,-7" +
            "c0,-2.19 1.01,-4.14 2.58,-5.42L6.17,5.17C4.23,6.82 3,9.26 3,12c0,4.97 4.03,9 9,9s9,-4.03 9,-9" +
            "c0,-2.74 -1.23,-5.18 -3.17,-6.83z",
    )
    val AcUnit = icon(
        "AcUnit",
        "M22,11h-4.17l3.24,-3.24 -1.41,-1.42L15,11h-2V9l4.66,-4.66 -1.42,-1.41L13,6.17V2h-2v4.17L7.76,2.93 6.34,4.34 " +
            "11,9v2H9L4.34,6.34 2.93,7.76 6.17,11H2v2h4.17l-3.24,3.24 1.41,1.42L9,13h2v2l-4.66,4.66 1.42,1.41L11,17.83V22h2" +
            "v-4.17l3.24,3.24 1.42,-1.41L13,15v-2h2l4.66,4.66 1.41,-1.42L17.83,13H22z",
    )
    val Sunny = icon(
        "Sunny",
        "M6.76,4.84l-1.8,-1.79 -1.41,1.41 1.79,1.79 1.42,-1.41zM4,10.5H1v2h3v-2zM13,0.55h-2V3.5h2V0.55z" +
            "M20.45,4.46l-1.41,-1.41 -1.79,1.79 1.41,1.41 1.79,-1.79zM17.24,18.16l1.79,1.8 1.41,-1.41 -1.8,-1.79 -1.4,1.4z" +
            "M20,10.5v2h3v-2h-3zM12,5.5c-3.31,0 -6,2.69 -6,6s2.69,6 6,6 6,-2.69 6,-6 -2.69,-6 -6,-6zM11,22.45h2V19.5h-2v2.95z" +
            "M3.55,18.54l1.41,1.41 1.79,-1.8 -1.41,-1.41 -1.79,1.8z",
    )
    val Remove = icon("Remove", "M19,13H5v-2h14v2z")

    private fun icon(name: String, path: String) = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black)).build()
}
