package com.deckers22.notes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.deckers22.notes.data.ChecklistItem

/**
 * Edits a checklist: a tick box and a text field per item. Ticked items stay where they are
 * and are struck through. Enter in an item starts a new item below it.
 */
@Composable
fun ChecklistEditor(
    items: List<ChecklistItem>,
    onChange: (List<ChecklistItem>) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Index of the item that should get the keyboard focus (a new, still empty list: the first).
    var focusIndex by remember {
        mutableStateOf(if (items.size == 1 && items[0].text.isEmpty()) 0 else null)
    }

    LazyColumn(modifier = modifier, contentPadding = PaddingValues(bottom = 16.dp)) {
        itemsIndexed(items) { index, item ->
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(focusIndex) {
                if (focusIndex == index) {
                    focusRequester.requestFocus()
                    focusIndex = null
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
                Checkbox(
                    checked = item.checked,
                    onCheckedChange = { checked -> onChange(items.replaced(index, listOf(item.copy(checked = checked)))) },
                )
                TextField(
                    value = item.text,
                    onValueChange = { text ->
                        // Enter (or pasted text with several lines) splits the item.
                        val parts = text.split('\n')
                        val replacement = parts.mapIndexed { n, part ->
                            if (n == 0) item.copy(text = part) else ChecklistItem(part)
                        }
                        onChange(items.replaced(index, replacement))
                        if (parts.size > 1) focusIndex = index + parts.size - 1
                    },
                    placeholder = { Text("List item") },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        textDecoration = if (item.checked) TextDecoration.LineThrough else null,
                        color = if (item.checked) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    ),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = transparentTextFieldColors(),
                    modifier = Modifier.weight(1f).focusRequester(focusRequester),
                )
                IconButton(onClick = { onChange(items.replaced(index, emptyList())) }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Remove item",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onChange(items + ChecklistItem(""))
                        focusIndex = items.size
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(16.dp))
                Text("Add item", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A copy of the list with the element at [index] replaced by [replacement] (which may be empty). */
private fun List<ChecklistItem>.replaced(index: Int, replacement: List<ChecklistItem>): List<ChecklistItem> =
    take(index) + replacement + drop(index + 1)
