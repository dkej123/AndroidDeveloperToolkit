package dev.acme.adbtoolbox.application.mirroring

import dev.acme.adbtoolbox.domain.mirroring.FakeMirroringOptionsRepository
import dev.acme.adbtoolbox.domain.mirroring.MirroringOptionFieldError
import dev.acme.adbtoolbox.domain.mirroring.MirroringOptions
import dev.acme.adbtoolbox.domain.mirroring.MirroringOptionsDraft
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class MirroringOptionsUseCaseTest {

    @Test
    fun `load returns the persisted mirroring options`() = runTest {
        val repository = FakeMirroringOptionsRepository(MirroringOptions(stayAwake = true, maxSize = 1280))
        val useCase = MirroringOptionsUseCase(repository)

        useCase.load() shouldBe MirroringOptions(stayAwake = true, maxSize = 1280)
    }

    @Test
    fun `invalid apply reports every validation error without writing`() = runTest {
        val repository = FakeMirroringOptionsRepository()
        val useCase = MirroringOptionsUseCase(repository)

        val result = useCase.apply(MirroringOptionsDraft(maxSize = -1, videoBitRateMbps = -1))

        result shouldBe MirroringOptionsApplyResult.Invalid(
            setOf(MirroringOptionFieldError.MaxSizeOutOfRange, MirroringOptionFieldError.VideoBitRateOutOfRange),
        )
        repository.writes shouldBe emptyList()
    }

    @Test
    fun `valid apply persists the validated options`() = runTest {
        val repository = FakeMirroringOptionsRepository()
        val useCase = MirroringOptionsUseCase(repository)

        val result = useCase.apply(
            MirroringOptionsDraft(stayAwake = true, showTouches = true, maxSize = 1920, videoBitRateMbps = 8),
        )

        val expected = MirroringOptions(stayAwake = true, showTouches = true, maxSize = 1920, videoBitRateMbps = 8)
        result shouldBe MirroringOptionsApplyResult.Applied(expected)
        repository.writes shouldBe listOf(expected)
    }
}
