/*
 *
 * Copyright 2025-2026 The FairScan authors
 * Copyright 2026 Ruben Matias
 *
 * Modified by Ruben Matias in 2026.
 * This file is part of the Nopalito Scan fork.
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 *
 */

package nopalito.app.ui.screens.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nopalito.app.R
import nopalito.app.i18n.AppLanguage
import nopalito.app.i18n.LegalDocument
import nopalito.app.i18n.readLegalDocument
import nopalito.app.i18n.stringFor

/**
 * First-run language selection + legal acceptance screen. Keeps the NopalitoScan
 * brand: green → purple gradient, rounded cards, the real app logo and a
 * primary action button.
 *
 * The language options are generated from [AppLanguage.supported], so adding a
 * new language to the central list automatically shows it here. Below them the
 * user must accept BOTH the Terms and Conditions and the Privacy Policy — the
 * "Accept and continue" button stays disabled until the acceptance checkbox is
 * ticked, and leaving (back / exit) without accepting shows a confirmation.
 *
 * Every text on this screen follows the *selected* language, so it updates
 * instantly before the locale is applied.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    languages: List<AppLanguage>,
    selected: AppLanguage,
    onLanguageSelected: (AppLanguage) -> Unit,
    accepted: Boolean,
    onAcceptedChange: (Boolean) -> Unit,
    onContinue: () -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    // The whole screen follows the *selected* language, so the picked option
    // (and the button text) updates instantly before the locale is applied.
    val localized: (Int) -> String = { resId -> context.stringFor(resId, selected.locale) }

    var showExitDialog by remember { mutableStateOf(false) }
    var openDocument by remember { mutableStateOf<LegalDocument?>(null) }

    // Back / exit without accepting: ask before leaving NopalitoScan.
    BackHandler { showExitDialog = true }

    // Acceptance requires a language too (there is always one selected, so in
    // practice the checkbox is what gates the button).
    val canContinue = accepted && selected.code.isNotBlank()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.tertiary,
                    )
                )
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp, vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Real NopalitoScan logo (found in the drawable resources).
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(120.dp)
                    .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(32.dp))
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = stringResource(R.string.app_name),
                    tint = Color.Unspecified,
                    modifier = Modifier.size(96.dp),
                )
            }

            Spacer(Modifier.height(28.dp))

            Text(
                text = localized(R.string.welcome_language_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(10.dp))

            Text(
                text = localized(R.string.welcome_language_question),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.85f),
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(32.dp))

            languages.forEach { language ->
                LanguageOption(
                    language = language,
                    isSelected = language == selected,
                    onClick = { onLanguageSelected(language) },
                )
                Spacer(Modifier.height(12.dp))
            }

            Spacer(Modifier.height(24.dp))

            // Legal acceptance: one toggleable row that must be ticked to continue.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .toggleable(
                        value = accepted,
                        role = Role.Checkbox,
                        onValueChange = onAcceptedChange,
                    )
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                CircularAcceptanceIndicator(checked = accepted)
                Spacer(Modifier.width(14.dp))
                Text(
                    text = localized(R.string.legal_accept_text),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                TextButton(onClick = { openDocument = LegalDocument.TERMS }) {
                    Text(
                        text = localized(R.string.legal_terms_link),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        textDecoration = TextDecoration.Underline,
                        color = Color.White.copy(alpha = 0.95f),
                    )
                }
                TextButton(onClick = { openDocument = LegalDocument.PRIVACY }) {
                    Text(
                        text = localized(R.string.legal_privacy_link),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        textDecoration = TextDecoration.Underline,
                        color = Color.White.copy(alpha = 0.95f),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = onContinue,
                enabled = canContinue,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = MaterialTheme.colorScheme.primary,
                    disabledContainerColor = Color.White.copy(alpha = 0.55f),
                    disabledContentColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp),
            ) {
                Text(
                    text = localized(R.string.legal_accept_continue),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text(stringResource(R.string.app_name)) },
            text = { Text(localized(R.string.legal_exit_dialog_message)) },
            confirmButton = {
                TextButton(onClick = { showExitDialog = false }) {
                    Text(
                        text = localized(R.string.legal_stay_here),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showExitDialog = false
                    onExit()
                }) {
                    Text(
                        text = localized(R.string.legal_exit_app),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
        )
    }

    val document = openDocument
    if (document != null) {
        LegalDocumentDialog(
            document = document,
            language = selected,
            title = localized(
                if (document == LegalDocument.TERMS) {
                    R.string.legal_terms_link
                } else {
                    R.string.legal_privacy_link
                }
            ),
            onDismiss = { openDocument = null },
        )
    }
}

@Composable
private fun CircularAcceptanceIndicator(
    checked: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = CircleShape
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(26.dp)
            .clip(shape)
            .background(
                if (checked) Color.White else Color.White.copy(alpha = 0.08f),
                shape,
            )
            .border(
                width = 1.5.dp,
                color = if (checked) Color.White else Color.White.copy(alpha = 0.55f),
                shape = shape,
            ),
    ) {
        if (checked) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Shows one legal document (from assets) as a full-screen, formatted viewer. */
@Composable
private fun LegalDocumentDialog(
    document: LegalDocument,
    language: AppLanguage,
    title: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val content by produceState<String?>(initialValue = null, document, language) {
        value = withContext(Dispatchers.IO) { context.readLegalDocument(language, document) }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column {
                // Brand gradient header (extends behind the status bar).
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.tertiary,
                                )
                            )
                        )
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(start = 20.dp, top = 14.dp, end = 8.dp, bottom = 14.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.close),
                                tint = Color.White,
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                ) {
                    val doc = content
                    if (doc == null) {
                        Text(
                            text = stringResource(R.string.legal_document_error),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    } else {
                        MarkdownDocument(doc)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(onClick = onDismiss) {
                        Text(stringResource(R.string.close))
                    }
                }
            }
        }
    }
}

private sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class Bullet(val text: String) : MarkdownBlock
}

private fun parseMarkdown(content: String): List<MarkdownBlock> =
    content.lineSequence().mapNotNull { raw ->
        val line = raw.trimEnd()
        when {
            line.isEmpty() -> null
            line.startsWith("### ") -> MarkdownBlock.Heading(3, line.removePrefix("### ").trim())
            line.startsWith("## ") -> MarkdownBlock.Heading(2, line.removePrefix("## ").trim())
            line.startsWith("# ") -> MarkdownBlock.Heading(1, line.removePrefix("# ").trim())
            line.startsWith("- ") -> MarkdownBlock.Bullet(line.removePrefix("- ").trim())
            else -> MarkdownBlock.Paragraph(line)
        }
    }.toList()

/** Lightweight renderer for the small markdown subset used by the legal docs. */
@Composable
private fun MarkdownDocument(content: String) {
    val blocks = remember(content) { parseMarkdown(content) }
    Column {
        blocks.forEachIndexed { _, block ->
            when (block) {
                is MarkdownBlock.Heading -> Text(
                    text = block.text,
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    fontWeight = if (block.level == 1) FontWeight.Bold else FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )

                is MarkdownBlock.Bullet -> Row(
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    InlineText(
                        text = block.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }

                is MarkdownBlock.Paragraph -> InlineText(
                    text = block.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(
                Modifier.height(if (block is MarkdownBlock.Heading) 14.dp else 10.dp)
            )
        }
    }
}

/** Renders inline markdown `**bold**` as styled spans. */
@Composable
private fun InlineText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val annotated = remember(text) {
        buildAnnotatedString {
            var last = 0
            for (match in Regex("\\*\\*(.+?)\\*\\*").findAll(text)) {
                append(text.substring(last, match.range.first))
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(match.groupValues[1])
                }
                last = match.range.last + 1
            }
            append(text.substring(last))
        }
    }
    Text(
        text = annotated,
        style = style,
        color = color,
        modifier = modifier,
    )
}

@Composable
private fun LanguageOption(
    language: AppLanguage,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f),
        border = BorderStroke(
            if (isSelected) 1.5.dp else 0.5.dp,
            if (isSelected) Color.White else Color.White.copy(alpha = 0.25f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = language.flag,
                style = MaterialTheme.typography.headlineSmall,
                fontSize = 26.sp,
            )
            Spacer(Modifier.width(14.dp))
            Text(
                text = language.nativeName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            if (isSelected) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(24.dp)
                        .background(Color.White, RoundedCornerShape(12.dp))
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}
