package com.mtgofa.carinfo.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** The dashboard "check engine" lamp: an engine block seen from the side. Tinted by the caller. */
val CheckEngineIcon: ImageVector = ImageVector.Builder("CheckEngine", 24.dp, 24.dp, 24f, 24f)
    .addPath(
        // Valve cover with oil cap on top, intake on the right, exhaust plug on the left.
        pathData = PathParser().parsePathString(
            "M7,4h6v2h-2v1h4l2,2h2v-2h2v9h-2v-2h-2v3l-2,2h-8l-2,-2v-2h-2v3h-2v-8h2v3h2v-4l2,-2h1v-1h-2z"
        ).toNodes(),
        fill = SolidColor(Color.Black),
    )
    .build()
