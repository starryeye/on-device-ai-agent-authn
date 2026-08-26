package dev.starryeye.ondeviceagent.model

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * 에이전트가 쓸 `.litertlm` 가중치 파일을 확보한다.
 *
 * LiteRT-LM은 파일 경로를 받을 뿐 모델을 스스로 가져오지 않으므로, 기기에 올려놓는 일은 앱의
 * 몫이다. 디렉터리에 이미 있는 파일은 그대로 쓰므로 `adb push`도 통한다.
 *
 * 이 파일은 ADK를 모른다. 파일을 찾고 받아오는 일만 한다.
 */
object ModelStore {

  /** 받아올 모델. 툴 호출이 가능한 `.litertlm` 모델이면 무엇이든 된다. */
  private const val REPO = "litert-community/gemma-4-E2B-it-litert-lm"
  private const val FILE_NAME = "gemma-4-E2B-it.litertlm"
  private const val REVISION = "main"

  /** 다운로드를 시작하기 전에 사용자에게 비용을 알리기 위한 대략적인 크기. */
  const val DOWNLOAD_SIZE_LABEL: String = "2.5 GB"

  private const val EXTENSION = ".litertlm"
  private const val PARTIAL_SUFFIX = ".part"

  /** 버퍼마다가 아니라 이 횟수만큼만 진행률을 낸다. */
  private const val PROGRESS_STEPS = 200

  private const val TIMEOUT_MILLIS = 30_000

  /**
   * [directory]에서 쓸 모델을 고른다. 직접 넣은 파일이 내려받은 파일을 이긴다 — 그래야
   * `adb push`한 모델이 아무것도 지우지 않고 우선한다. 직접 넣은 것이 여럿이면 이름순 첫 번째다.
   *
   * 받다 만 파일은 `.litertlm.part`로 끝나므로 확장자 검사에서 자연히 걸러진다.
   */
  fun selectModelFile(directory: File): File? {
    val candidates =
      directory
        .listFiles { file -> file.isFile && file.name.endsWith(EXTENSION) }
        .orEmpty()
        .sortedBy { it.name }
    return candidates.firstOrNull { it.name != FILE_NAME } ?: candidates.firstOrNull()
  }

  /** 쓸 수 있는 모델, 아직 아무것도 없으면 null. */
  fun find(context: Context): File? = selectModelFile(directory(context))

  /**
   * [FILE_NAME]을 내려받으며 완료 비율을 낸다. 바이트는 임시 파일에 쓰고 전송이 끝난 뒤에만
   * 이름을 바꾼다 — 중단된 다운로드가 멀쩡한 모델로 오인되지 않게 하려는 것이다.
   */
  fun download(context: Context): Flow<Float> =
    flow {
        emit(0f)
        val partial = File(directory(context), FILE_NAME + PARTIAL_SUFFIX)
        try {
          val connection = URL(downloadUrl()).openConnection() as HttpURLConnection
          connection.connectTimeout = TIMEOUT_MILLIS
          connection.readTimeout = TIMEOUT_MILLIS
          try {
            // 이게 없으면 오류 페이지가 모델인 양 파일로 쓰인다.
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
              "모델 다운로드가 HTTP ${connection.responseCode}로 실패했습니다."
            }
            val total = connection.contentLengthLong
            val step = if (total > 0) total / PROGRESS_STEPS else Long.MAX_VALUE
            var copied = 0L
            var reported = 0L
            connection.inputStream.use { input ->
              partial.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                  coroutineContext.ensureActive()
                  val read = input.read(buffer)
                  if (read < 0) break
                  output.write(buffer, 0, read)
                  copied += read
                  if (copied - reported >= step) {
                    reported = copied
                    emit(copied.toFloat() / total)
                  }
                }
              }
            }
          } finally {
            connection.disconnect()
          }
          check(partial.renameTo(File(directory(context), FILE_NAME))) {
            "받은 모델을 제자리로 옮기지 못했습니다."
          }
        } catch (t: Throwable) {
          // 처음부터 다시 받는 재시도를 위해 반쯤 받은 수 GB를 남겨둘 이유가 없다.
          val unused = partial.delete()
          throw t
        }
        emit(1f)
      }
      .flowOn(Dispatchers.IO)

  /** 다운로드 안내와 함께 보여줄 `adb push` 목적지. */
  fun pushDirectory(context: Context): String = directory(context).absolutePath

  private fun directory(context: Context): File =
    context.getExternalFilesDir(null) ?: context.filesDir

  private fun downloadUrl(): String = "https://huggingface.co/$REPO/resolve/$REVISION/$FILE_NAME"
}
