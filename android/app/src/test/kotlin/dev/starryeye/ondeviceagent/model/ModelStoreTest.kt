package dev.starryeye.ondeviceagent.model

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelStoreTest {

  @get:Rule val folder = TemporaryFolder()

  @Test
  fun `비어 있는 디렉터리에서는 모델을 찾지 못한다`() {
    assertNull(ModelStore.selectModelFile(folder.root))
  }

  @Test
  fun `내려받은 모델만 있으면 그것을 쓴다`() {
    val downloaded = folder.newFile("gemma-4-E2B-it.litertlm")

    assertEquals(downloaded, ModelStore.selectModelFile(folder.root))
  }

  @Test
  fun `직접 넣은 모델이 내려받은 모델보다 우선한다`() {
    folder.newFile("gemma-4-E2B-it.litertlm")
    val pushed = folder.newFile("my-own-model.litertlm")

    assertEquals(pushed, ModelStore.selectModelFile(folder.root))
  }

  @Test
  fun `직접 넣은 모델이 여럿이면 이름순 첫 번째를 쓴다`() {
    folder.newFile("z-model.litertlm")
    val first = folder.newFile("a-model.litertlm")

    assertEquals(first, ModelStore.selectModelFile(folder.root))
  }

  @Test
  fun `받다 만 파일은 모델로 취급하지 않는다`() {
    folder.newFile("gemma-4-E2B-it.litertlm.part")

    assertNull(ModelStore.selectModelFile(folder.root))
  }
}
