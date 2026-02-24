package dev.dertyp.synara

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.dertyp.synara.ui.runTransparentWindow

fun main() = runTransparentWindow(
    width = 450,
    height = 300,
    minWidth = 450,
    minHeight = 300,
) {
    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        var clicks by remember { mutableIntStateOf(0) }
        Text("Welcome to Synara $clicks", style = MaterialTheme.typography.displayMedium)
        Button(
            onClick = { clicks++ },
        ) {
            Text("Click me")
        }
        var text by remember { mutableStateOf("") }
        TextField(
            value = text,
            onValueChange = { text = it },
        )
    }
}
