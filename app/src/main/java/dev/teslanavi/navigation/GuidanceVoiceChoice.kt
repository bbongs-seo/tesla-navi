package dev.teslanavi.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Hint = Color(0xFFA2AEBD)

@Composable
fun GuidanceVoiceChoice(selected: GuidanceVoice, model: ModelState, onSelect: (GuidanceVoice) -> Unit,
    onDownload: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit) {
    val ready = model.phase == ModelPhase.READY
    val active = if (ready) selected else GuidanceVoice.SYSTEM
    Column {
        Text("안내 음성", fontWeight = FontWeight.SemiBold)
        VoiceOption("휴대폰 기본 음성", "설치된 TTS 엔진으로 바로 안내합니다.", active == GuidanceVoice.SYSTEM, true) {
            onSelect(GuidanceVoice.SYSTEM)
        }
        VoiceOption("Supertonic 2 AI 음성", when (model.phase) {
            ModelPhase.READY -> "설치됨 · 인터넷 없이 휴대폰에서 합성합니다. 처음 말하는 문장은 잠시 늦을 수 있습니다."
            ModelPhase.DOWNLOADING -> model.message ?: "음성 모델을 받는 중 ${(model.progress * 100).toInt()}%"
            ModelPhase.FAILED -> model.message ?: "음성 모델을 받지 못했습니다."
            ModelPhase.MISSING -> "음성 모델 약 85MB를 한 번 받아야 합니다. Wi-Fi 연결을 권장합니다."
        }, active == GuidanceVoice.SUPERTONIC, ready) { onSelect(GuidanceVoice.SUPERTONIC) }
        when (model.phase) {
            ModelPhase.MISSING -> OutlinedButton(onClick = onDownload, modifier = Modifier.fillMaxWidth()) { Text("음성 모델 받기 (85MB)") }
            ModelPhase.FAILED -> OutlinedButton(onClick = onDownload, modifier = Modifier.fillMaxWidth()) { Text("다시 받기") }
            ModelPhase.DOWNLOADING -> {
                LinearProgressIndicator(progress = { model.progress }, modifier = Modifier.fillMaxWidth())
                if (model.progress < 1f) TextButton(onClick = onCancel) { Text("받기 취소") }
            }
            ModelPhase.READY -> TextButton(onClick = onDelete) { Text("음성 모델 삭제") }
        }
    }
}

@Composable
private fun VoiceOption(title: String, detail: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, enabled, Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column {
            Text(title)
            Text(detail, fontSize = 12.sp, color = Hint)
        }
    }
}
