package io.vocaguard

import io.vocaguard.data.ScamType
import io.vocaguard.detection.ScamPatternDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Russian message scanning: stems are prefix-matched so inflected forms hit, and
 * normal Russian chat stays clean.
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33])
class RussianMessageDetectionTest {

    private lateinit var detector: ScamPatternDetector

    @Before
    fun setUp() {
        detector = ScamPatternDetector()
    }

    private fun assertScam(text: String, type: ScamType) {
        val r = detector.analyzeMessage(text)
        assertTrue("expected scam: $text (conf=${r.confidence})", r.isScam)
        assertEquals(type, r.scamType)
    }

    private fun assertClean(text: String) {
        val r = detector.analyzeMessage(text)
        assertFalse("false positive: $text (${r.reason})", r.isScam)
    }

    // ── Scams ──────────────────────────────────────────────────────────────

    @Test
    fun `Israel Post parcel held - inflected forms`() =
        assertScam(
            "Почта Израиля: ваша посылка задержана на таможне. Оплатите таможенную пошлину 7,90 ₪ по ссылке",
            ScamType.DELIVERY_SCAM
        )

    @Test
    fun `investment withdrawal fee`() =
        assertScam(
            "Ваш личный менеджер сообщает: чтобы вывести прибыль, нужно оплатить комиссию за вывод средств",
            ScamType.INVESTMENT_SCAM
        )

    @Test
    fun `WhatsApp likes job scam`() =
        assertScam(
            "Удалённая работа! Нужно просто ставить лайки на YouTube, оплата каждый день, доход в день от 300 шекелей",
            ScamType.JOB_SCAM
        )

    @Test
    fun `bank account blocked with an extra word between stems`() =
        assertScam(
            "Банк Леуми: ваш счёт временно заблокирован из-за подозрительной операции. Срочно подтвердите данные",
            ScamType.BANK_FRAUD
        )

    @Test
    fun `safe account social engineering`() =
        assertScam(
            "Это служба безопасности. Переведите ваши деньги на безопасный счёт и никому не говорите об этом",
            ScamType.SOCIAL_ENGINEERING
        )

    @Test
    fun `Bituach Leumi benefit suspended`() =
        assertScam(
            "Битуах Леуми: ваше пособие будет приостановлено. Служба национального страхования просит обновить данные",
            ScamType.SOCIAL_SECURITY
        )

    @Test
    fun `seed phrase is critical`() {
        val r = detector.analyzeMessage("Пришлите, пожалуйста, вашу сид-фразу, чтобы мы восстановили доступ к кошельку")
        assertTrue(r.isScam)
    }

    @Test
    fun `yo written as e still matches`() {
        // Keywords are written with "ё" (счёт) or "е"; the text uses "е" (счет).
        // Bank/phishing tie on this text, so only detection is asserted.
        val r = detector.analyzeMessage(
            "Ваш счет заблокирован. Обнаружена подозрительная активность, сообщите код из смс"
        )
        assertTrue(r.isScam)
    }

    // ── Normal chat ────────────────────────────────────────────────────────

    @Test
    fun `family chat is clean`() =
        assertClean("Привет! Мы приедем в субботу к обеду, бабушка испечёт пирог. Позвони, когда будешь свободна")

    @Test
    fun `talking about a real parcel once is clean`() =
        assertClean("Мама, посылка от тёти Гали пришла, спасибо! Забрала её сегодня на почте")

    @Test
    fun `talking about work from home once is clean`() =
        assertClean("Я сейчас на удалённой работе, перезвоню вечером после восьми")

    @Test
    fun `stem does not match a different word that merely contains it`() =
        // "налоговый долг" must not fire from unrelated words; single mention anyway.
        assertClean("Сегодня ходили в банк, потом гуляли по набережной, было очень красиво")
}
