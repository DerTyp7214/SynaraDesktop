package dev.dertyp.synara

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import dev.dertyp.synara.theme.Config

@Composable
fun SynaraView() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Welcome to Synara",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.displayMedium
        )
        Button(
            onClick = { Config.setDarkTheme(!Config.darkTheme.value) },
        ) {
            Text("Click me")
        }
        Box {
            var text by remember { mutableStateOf("") }
            InternalTextField(
                value = text,
                onValueChange = { text = it }
            )
        }
        Box {
            var text by remember { mutableStateOf("") }
            InternalTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onDone = { println("Done") },
                    onGo = { println("Go") },
                    onNext = { println("Next") },
                    onPrevious = { println("Previous") },
                    onSearch = { println("Search") },
                    onSend = { println("Send") }
                ),
            )
        }
    }
}
