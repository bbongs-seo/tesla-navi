package dev.teslanavi.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val Hint = Color(0xFFA2AEBD)

/** Radio (24dp) plus its gap: option details and actions start here so they line up under the titles. */
private val OptionIndent = 36.dp

@Composable
fun GuidanceVoiceChoice(selected: GuidanceVoice, model: ModelState, onSelect: (GuidanceVoice) -> Unit,
    onDownload: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit,
    speaker: SupertonicSpeaker = SupertonicSpeaker.F1, onSpeaker: (SupertonicSpeaker) -> Unit = {}) {
    val ready = model.phase == ModelPhase.READY
    val active = if (ready) selected else GuidanceVoice.SYSTEM
    var showInfo by remember { mutableStateOf(false) }
    var showSpeakers by remember { mutableStateOf(false) }
    Column {
        Text("안내 음성", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        VoiceOption("휴대폰 기본 음성", "휴대폰에 설치된 TTS 엔진", active == GuidanceVoice.SYSTEM, true) {
            onSelect(GuidanceVoice.SYSTEM)
        }
        VoiceOption("Supertonic 2 AI 음성", when (model.phase) {
            ModelPhase.READY -> "설치됨 · 인터넷 없이 합성"
            ModelPhase.DOWNLOADING -> model.message ?: "음성 모델 받는 중 ${(model.progress * 100).toInt()}%"
            ModelPhase.FAILED -> model.message ?: "음성 모델을 받지 못했습니다."
            ModelPhase.MISSING -> "음성 모델(85MB)을 받아야 쓸 수 있음"
        }, active == GuidanceVoice.SUPERTONIC, ready, onInfo = { showInfo = true }) { onSelect(GuidanceVoice.SUPERTONIC) }
        Column(Modifier.padding(start = OptionIndent), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (model.phase) {
                ModelPhase.MISSING -> OutlinedButton(onClick = onDownload, modifier = Modifier.fillMaxWidth()) { Text("음성 모델 받기 (85MB)") }
                ModelPhase.FAILED -> OutlinedButton(onClick = onDownload, modifier = Modifier.fillMaxWidth()) { Text("다시 받기") }
                ModelPhase.DOWNLOADING -> {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(progress = { model.progress }, modifier = Modifier.fillMaxWidth())
                    if (model.progress < 1f) InlineTextButton("받기 취소", onCancel)
                }
                ModelPhase.READY -> {
                    if (active == GuidanceVoice.SUPERTONIC) {
                        OutlinedButton(onClick = { showSpeakers = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("목소리 변경 · ${speaker.label}")
                        }
                        Text("여성 5개 · 남성 5개 중 선택한 뒤 아래 카메라 음성 테스트로 들어보세요.", fontSize = 13.sp, color = Hint)
                    }
                    InlineTextButton("음성 모델 삭제", onDelete)
                }
            }
        }
    }
    if (showSpeakers) AlertDialog(onDismissRequest = { showSpeakers = false },
        title = { Text("Supertonic 목소리") },
        text = {
            Column(Modifier.height(340.dp).verticalScroll(rememberScrollState())) {
                SupertonicSpeaker.entries.forEach { option ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .selectable(speaker == option, role = Role.RadioButton, onClick = {
                            onSpeaker(option); showSpeakers = false
                        }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = speaker == option, onClick = null)
                        Text(option.label)
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { showSpeakers = false }) { Text("닫기") } })
    if (showInfo) AlertDialog(onDismissRequest = { showInfo = false },
        title = { Text("Supertonic 2 AI 음성") },
        text = {
            Text("Supertone이 공개한 음성 합성 모델로, 휴대폰 안에서 안내 문장을 직접 만듭니다. 처음 한 번 음성 모델(약 85MB)을 받아야 하니 " +
                "Wi-Fi에서 받으세요. 그다음부터는 인터넷이 없어도 됩니다.\n\n" +
                "처음 말하는 문장은 만드는 데 Galaxy S25+ 기준 0.5초쯤 걸려 안내가 조금 늦게 시작될 수 있습니다. " +
                "한 번 말한 문장과 다가오는 카메라 안내 문장은 미리 만들어 두어서 바로 나옵니다.\n\n" +
                "문장을 만들지 못하면 그 문장은 휴대폰 기본 음성으로 안내합니다. 음성 모델은 이 화면에서 언제든 지울 수 있고, " +
                "모델 라이선스는 OpenRAIL-M입니다.")
        },
        confirmButton = { TextButton(onClick = { showInfo = false }) { Text("닫기") } })
}

/** One radio option. The radio sits on the title line; the whole row is the touch target. */
@Composable
private fun VoiceOption(title: String, detail: String, selected: Boolean, enabled: Boolean,
    onInfo: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .selectable(selected, enabled, Role.RadioButton, onClick = onClick).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(OptionIndent - 24.dp)) {
        RadioButton(selected = selected, onClick = null, enabled = enabled, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Row(Modifier.height(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 16.sp, color = if (enabled) Color.Unspecified else Hint)
                if (onInfo != null) IconButton(onClick = onInfo, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.Info, contentDescription = "$title 안내", tint = Hint, modifier = Modifier.size(18.dp))
                }
            }
            Text(detail, fontSize = 13.sp, color = Hint, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A text button whose label starts on the same line as the option details above it. */
@Composable
private fun InlineTextButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(vertical = 8.dp)) { Text(label) }
}
