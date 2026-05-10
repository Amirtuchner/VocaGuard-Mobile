#!/usr/bin/env python3
"""
VocaGuard Scam Detector Model Training Script

This script trains a TensorFlow Lite model for scam call detection.
"""

import tensorflow as tf
from tensorflow import keras
import numpy as np
import pandas as pd
from sklearn.model_selection import train_test_split
import re
import math
from collections import Counter

# Configuration
NUM_CLASSES = 11  # 0: legitimate, 1-10: scam types
EPOCHS = 100
BATCH_SIZE = 32
VALIDATION_SPLIT = 0.2

NUM_FEATURES = 42  # 26 keyword/text + 5 conversational behaviour + 6 audio/metadata + 5 call-centre signals


# ---------------------------------------------------------------------------
# Conversational behaviour helpers — must mirror TextPreprocessor.kt exactly
# ---------------------------------------------------------------------------

def _repetition_score(text):
    """Fraction of 3-word n-grams that are repeated — signals scripted speech."""
    words = [w for w in text.split() if len(w) > 2]
    if len(words) < 3:
        return 0.0
    trigrams = [f"{words[i]} {words[i+1]} {words[i+2]}" for i in range(len(words) - 2)]
    repeated = len(trigrams) - len(set(trigrams))
    return min(repeated / len(trigrams), 1.0)


def _question_density(text):
    """Ratio of question words to total words — interrogation/pressure signal."""
    words = [w for w in text.split() if w]
    if not words:
        return 0.0
    q_words = {
        # English
        "who", "what", "when", "where", "why", "how", "can", "could", "would", "do", "are", "is",
        # Hebrew
        "מה", "מי", "מתי", "איפה", "למה", "איך", "האם", "כמה", "היכן",
        # Arabic
        "ما", "من", "متى", "أين", "لماذا", "كيف", "هل", "كم",
        # Spanish
        "quién", "qué", "cuándo", "dónde", "por qué", "cómo", "puede", "podría",
        # French
        "qui", "quoi", "quand", "où", "pourquoi", "comment", "peut", "pourrait",
    }
    return min(sum(1 for w in words if w in q_words) / len(words), 1.0)


def _has_long_monologue(text):
    """1.0 if any sentence exceeds 50 words — suggests script reading."""
    segments = re.split(r'[.!?]+', text)
    return 1.0 if any(len([w for w in s.split() if w]) > 50 for s in segments) else 0.0


def _urgency_escalates(text):
    """1.0 if urgency terms are denser in the second half — classic scam escalation."""
    words = text.split()
    mid = len(words) // 2
    urgency_terms = [
        # English
        "urgent", "immediately", "right now", "act now", "final", "last chance", "failure to",
        # Russian
        "срочно", "немедленно", "последний шанс", "действуйте сейчас",
        # Hebrew
        "דחוף", "עכשיו", "מיד", "תוך שעה", "הזדמנות אחרונה", "ייחסם", "יינתק",
        # Arabic
        "عاجل", "الآن", "فوراً", "فرصة أخيرة",
        # Spanish
        "urgente", "inmediatamente", "ahora mismo", "última oportunidad", "actúe ahora",
        # French
        "immédiatement", "maintenant", "dernière chance", "agissez maintenant",
    ]
    first = " ".join(words[:mid])
    second = " ".join(words[mid:])
    first_count = sum(1 for t in urgency_terms if t in first)
    second_count = sum(1 for t in urgency_terms if t in second)
    return 1.0 if second_count > first_count else 0.0


def _has_repeated_openers(text):
    """1.0 if 3+ sentences share the same first word — hallmark of a read script."""
    sentences = [s.strip().split() for s in re.split(r'[.!?]+', text) if s.strip()]
    openers = [s[0].lower() for s in sentences if s]
    if len(openers) < 3:
        return 0.0
    return 1.0 if max(Counter(openers).values(), default=0) >= 3 else 0.0

def extract_features(text, avg_rms=0.0, rms_std_dev=0.0, silence_ratio=0.0,
                     had_long_silence=0.0, call_duration_sec=0.0, call_hour=12,
                     speaker_switches=0, noise_floor_db=0.0, speech_rate_wpm=0.0,
                     dtmf_detected=0.0):
    """
    Extract numerical features from text + optional audio/metadata signals.
    Must match TextPreprocessor.extractFeatures() in Android app.
    42 features total (26 keyword/text + 5 conversational + 6 audio/metadata +
    5 call-centre signals).

    All audio/metadata args default to 0 so existing transcript-only CSVs still work.
    """
    text_lower = text.lower()
    text_len = len(text)

    if text_len == 0:
        return [0.0] * NUM_FEATURES

    def flag(*keywords):
        return 1.0 if any(kw in text_lower for kw in keywords) else 0.0

    features = []

    # Features 1-6: Text statistics
    features.append(min(text_len / 1000.0, 1.0))                                         # 1: normalized length
    features.append(min(len(text.split()) / 100.0, 1.0))                                  # 2: normalized word count
    features.append(sum(c.isdigit() for c in text) / text_len)                            # 3: digit ratio
    features.append(text.count('!') / text_len)                                           # 4: exclamation ratio
    features.append(text.count('?') / text_len)                                           # 5: question ratio
    features.append(sum(c.isupper() for c in text) / text_len)                            # 6: uppercase ratio

    # Features 7-10: Generic scam signals
    features.append(flag(                                                                            # 7: urgency
        'urgent', 'immediately', 'right now', 'at once',
        'срочно', 'немедленно', 'сейчас же', 'прямо сейчас', 'незамедлительно',
        'עכשיו', 'מיד', 'דחוף', 'בדחיפות', 'תוך שעה', 'ללא דיחוי', 'מהר',
        'الآن', 'عاجل', 'فوراً', 'على الفور', 'بسرعة',
        'urgente', 'inmediatamente', 'ahora mismo', 'de inmediato',
        'immédiatement', 'maintenant', 'de suite', 'sans délai'))
    features.append(flag(                                                                            # 8: account blocked
        'suspended', 'locked', 'frozen', 'blocked',
        'заблокирован', 'заморожен', 'приостановлен', 'закрыт',
        'חסום', 'נחסם', 'הוקפא', 'מוקפא', 'מושעה', 'נחסמה', 'הוקפאה', 'נחסמת',
        'محظور', 'مجمد', 'معلق', 'موقوف', 'مغلق',
        'suspendido', 'bloqueado', 'congelado', 'cancelado',
        'suspendu', 'bloqué', 'gelé', 'résilié'))
    features.append(flag(                                                                            # 9: verification
        'verify', 'confirm', 'validate',
        'подтвердите', 'верифицируйте', 'проверьте', 'подтверждение',
        'אמת', 'אימות', 'לאמת', 'לאשר', 'לוודא', 'אישור', 'קוד אימות', 'קוד otp',
        'تحقق', 'أكد', 'تأكيد', 'التحقق', 'رمز التحقق', 'رمز otp',
        'verificar', 'confirmar', 'validar', 'verificación',
        'vérifier', 'confirmer', 'valider', 'vérification'))
    features.append(flag(                                                                            # 10: money
        'money', 'payment', 'funds', 'cash', 'pay',
        'деньги', 'оплата', 'средства', 'перевод', 'платёж', 'платеж',
        'כסף', 'תשלום', 'העברה', 'לשלם', 'ביט', 'פייבוקס', 'מזומן', 'העברת כסף',
        'المال', 'دفع', 'تحويل', 'مبلغ', 'أموال',
        'dinero', 'pago', 'transferencia', 'efectivo', 'pagar',
        'argent', 'paiement', 'virement', 'espèces', 'payer'))

    # Features 11-19: Category-specific keywords
    features.append(flag(                                                                            # 11: IRS/tax
        'irs', 'internal revenue', 'tax department', 'tax debt', 'back taxes', 'unpaid tax',
        'налоговая', 'налоги', 'налоговый долг', 'задолженность', 'налоговая служба', 'фнс',
        'מס הכנסה', 'רשות המסים', 'חוב מס', 'חוב לרשות', 'עיקול מס',
        'الضريبة', 'مصلحة الضرائب', 'ديون ضريبية', 'الإيرادات الداخلية',
        'servicio de impuestos', 'hacienda', 'deuda fiscal', 'impuestos',
        "impôts", 'service des impôts', 'dette fiscale', 'fisc'))
    features.append(flag(                                                                            # 12: legal threat
        'arrest', 'warrant', 'jail', 'prison', 'prosecution',
        'charges', 'law enforcement', 'officer',
        'арест', 'ордер на арест', 'тюрьма', 'уголовное дело', 'полиция',
        'следствие', 'обвинение', 'прокуратура',
        'מעצר', 'צו מעצר', 'תיק פלילי', 'כליאה', 'עצור', 'תביעה פלילית',
        'הוצאה לפועל', 'עיקול', 'צו', 'חקירה',
        'اعتقال', 'مذكرة اعتقال', 'قضية جنائية', 'الحجز', 'سجن', 'ملاحقة قضائية',
        'arresto', 'orden de arresto', 'cárcel', 'prisión', 'demanda', 'policía',
        'arrestation', "mandat d'arrêt", 'prison', 'poursuites', 'police'))
    features.append(flag(                                                                            # 13: tech threat
        'virus', 'malware', 'infected', 'spyware', 'ransomware',
        'remote access', 'anydesk', 'teamviewer',
        'вирус', 'вредоносное', 'заражён', 'взломан', 'хакер',
        'удалённый доступ', 'шпионское по',
        'וירוס', 'תוכנה זדונית', 'פרוץ', 'נגוע', 'גישה מרחוק',
        'teamviewer', 'anydesk', 'להוריד תוכנה', 'תיקון מחשב',
        'فيروس', 'برامج خبيثة', 'مخترق', 'وصول عن بعد',
        'virus informático', 'software malicioso', 'infectado', 'acceso remoto',
        'virus informatique', 'logiciel malveillant', 'infecté', 'accès à distance'))
    features.append(flag(                                                                            # 14: tech brand
        'microsoft', 'windows', 'apple', 'computer', 'device',
        'tech support', 'technical support',
        'майкрософт', 'виндовс', 'эппл', 'компьютер', 'техподдержка', 'техническая поддержка',
        'מיקרוסופט', 'חלונות', 'תמיכה טכנית', 'שירות לקוחות טכני', 'נציג תמיכה',
        'مايكروسوفت', 'ويندوز', 'آبل', 'دعم فني', 'خدمة العملاء التقنية',
        'soporte técnico', 'asistencia técnica',
        'support technique', 'assistance technique'))
    features.append(flag(                                                                            # 15: banking
        'bank', 'credit card', 'debit card', 'account number',
        'routing number', 'wire transfer', 'pin',
        'банк', 'кредитная карта', 'дебетовая карта', 'номер счёта',
        'реквизиты', 'пин-код', 'перевод средств',
        'בנק', 'כרטיס אשראי', 'מספר חשבון', 'פרטי בנק', 'פין', 'העברה בנקאית',
        'חשבון בנק', 'כרטיס חיוב',
        'بنك', 'بطاقة ائتمان', 'رقم الحساب', 'تحويل بنكي', 'بطاقة الخصم',
        'banco', 'tarjeta de crédito', 'número de cuenta', 'transferencia bancaria',
        'banque', 'carte de crédit', 'numéro de compte', 'virement bancaire', 'code pin'))
    features.append(flag(                                                                            # 16: lottery
        'won', 'winner', 'prize', 'lottery', 'sweepstakes', 'congratulations', 'reward',
        'выиграли', 'победитель', 'приз', 'лотерея', 'поздравляем', 'выигрыш', 'джекпот',
        'זכית', 'הגרלה', 'פרס', 'מזל טוב', 'זוכה', 'זכייה', 'הגרלת',
        'فزت', 'جائزة', 'يانصيب', 'مبروك', 'فائز', 'قرعة',
        'ganaste', 'lotería', 'premio', 'felicitaciones', 'ganador',
        'gagné', 'loterie', 'prix', 'félicitations', 'gagnant'))
    features.append(flag(                                                                            # 17: SSN/identity
        'social security', 'ssn', 'social security number', 'ss number', 'federal benefits',
        'снилс', 'пенсионный фонд', 'страховой номер', 'инн', 'паспортные данные',
        'תעודת זהות', 'מספר תעודת זהות', 'פרטים אישיים', 'מספר ביטוח לאומי', 'ת.ז',
        'رقم الهوية', 'بطاقة هوية', 'الهوية الوطنية', 'رقم الضمان الاجتماعي',
        'seguridad social', 'número de seguridad social', 'dni', 'documentos de identidad',
        'sécurité sociale', 'numéro de sécurité sociale', "carte d'identité", "pièce d'identité"))
    features.append(flag(                                                                            # 18: robocall
        'press one', 'press 1', 'recorded message', 'automated', 'warranty', 'extended warranty',
        'нажмите один', 'нажмите 1', 'записанное сообщение',
        'автоматическое уведомление', 'гарантия на автомобиль',
        'לחץ אחת', 'לחץ 1', 'הודעה מוקלטת', 'הודעה אוטומטית', 'אחריות מורחבת',
        'اضغط واحد', 'اضغط 1', 'رسالة مسجلة', 'آلية', 'ضمان ممتد',
        'presione 1', 'mensaje grabado', 'no cuelgue', 'llame de vuelta',
        'appuyez sur 1', 'message enregistré', 'ne raccrochez pas', 'rappeler'))
    features.append(flag(                                                                            # 19: phishing
        'password', 'credentials', 'login', 'username', 'click', 'link', 'update your',
        'пароль', 'логин', 'учётные данные', 'ссылка', 'обновите данные', 'войдите в систему',
        'סיסמה', 'פרטי כניסה', 'לחץ כאן', 'קישור', 'עדכן פרטים', 'היכנס',
        'פרטי משתמש', 'כניסה לחשבון',
        'كلمة المرور', 'بيانات الدخول', 'انقر هنا', 'رابط', 'تسجيل الدخول',
        'contraseña', 'haga clic', 'enlace', 'actualice su información',
        'mot de passe', 'cliquez', 'lien', 'mettez à jour vos informations'))

    # Features 20-26: More category signals
    features.append(flag(                                                                            # 20: insurance
        'insurance', 'medicare', 'medicaid', 'health plan', 'health insurance', 'coverage', 'enrollment',
        'страховка', 'медицинская страховка', 'полис', 'страхование', 'омс', 'дмс',
        'ביטוח', 'ביטוח בריאות', 'פוליסה', 'כיסוי ביטוחי', 'ביטוח לאומי', 'מגן',
        'تأمين', 'تأمين صحي', 'وثيقة تأمين', 'تغطية تأمينية',
        'seguro de salud', 'cobertura médica', 'póliza de seguro', 'seguro médico',
        'assurance maladie', 'assurance santé', 'couverture médicale', 'mutuelle'))
    features.append(flag(                                                                            # 21: investment
        'investment', 'trading', 'profit', 'returns',
        'broker', 'portfolio', 'invest', 'stock', 'crypto',
        'инвестиции', 'трейдинг', 'прибыль', 'доходность', 'брокер',
        'криптовалюта', 'акции', 'вложить', 'заработок', 'пассивный доход',
        'השקעה', 'מסחר', 'רווח', 'תשואה', 'ברוקר', 'קריפטו', 'ביטקוין',
        'להשקיע', 'הכפלת כסף', 'פורקס', 'מניות',
        'استثمار', 'تداول', 'ربح', 'عائد', 'وسيط', 'عملة مشفرة', 'بيتكوين',
        'inversión', 'ganancias', 'rentabilidad', 'criptomoneda', 'bitcoin', 'ingresos pasivos',
        'investissement', 'rendement', 'bénéfices', 'cryptomonnaie', 'bitcoin', 'revenus passifs'))
    features.append(flag(                                                                            # 22: payment method
        'gift card', 'bitcoin', 'western union', 'wire', 'cryptocurrency', 'prepaid card',
        'биткоин', 'криптовалюта', 'вестерн юнион', 'электронный кошелёк',
        'предоплата', 'подарочная карта',
        'גיפט קארד', 'ביטקוין', 'קריפטו', 'כרטיס מתנה', 'ביט', 'פייבוקס', 'העברה מיידית',
        'بطاقة هدية', 'بيتكوين', 'تحويل مالي', 'ويسترن يونيون',
        'tarjeta de regalo', 'bitcoin', 'western union', 'transferencia',
        'carte cadeau', 'bitcoin', 'western union', 'virement'))
    features.append(flag(                                                                            # 23: free offer
        'free', 'no cost', 'no charge', 'at no cost', 'qualify', 'eligible', 'complimentary',
        'бесплатно', 'без оплаты', 'имеете право', 'подходите', 'бесплатная консультация',
        'חינם', 'ללא עלות', 'זכאי', 'מגיע לך', 'בחינם', 'ללא תשלום',
        'مجاناً', 'مجاني', 'مؤهل', 'تستحق', 'بدون رسوم',
        'gratis', 'gratuito', 'califica', 'elegible', 'sin costo',
        'gratuit', 'sans frais', 'éligible', 'qualifie'))
    features.append(flag(                                                                            # 24: callback pressure
        'call back', 'call now', 'call immediately', 'call us', 'contact us', 'call this number',
        'перезвоните', 'позвоните сейчас', 'срочно позвоните', 'свяжитесь с нами', 'звоните немедленно',
        'התקשר עכשיו', 'חזור אלינו', 'התקשרו אלינו', 'צור קשר', 'התקשר למספר',
        'اتصل الآن', 'اتصل بنا', 'تواصل معنا', 'اتصل بهذا الرقم',
        'llámenos', 'llame ahora', 'contáctenos', 'llame a este número',
        'appelez-nous', 'appelez maintenant', 'contactez-nous', 'rappeler ce numéro'))
    features.append(flag(                                                                            # 25: deadline pressure
        'final notice', 'last chance', 'act now',
        'time is running out', 'do not delay', 'do not ignore', 'last warning', 'failure to',
        'последнее уведомление', 'последний шанс', 'действуйте сейчас',
        'время истекает', 'не игнорируйте', 'финальное предупреждение',
        'הודעה אחרונה', 'הזדמנות אחרונה', 'פג תוקף', 'תוך 24 שעות',
        'תוך 48 שעות', 'עד מחר', 'ייחסם', 'יינתק', 'יבוטל', 'יימחק',
        'إشعار نهائي', 'فرصة أخيرة', 'ينتهي', 'خلال 24 ساعة', 'آخر تحذير',
        'aviso final', 'última oportunidad', 'actúe ahora', 'tiempo agotado', 'último aviso',
        'avis final', 'dernière chance', 'agissez maintenant', 'délai expiré', 'dernier avertissement'))
    features.append(flag(                                                                            # 26: donation fraud
        'charity', 'donate', 'donation', 'help victims',
        'disaster relief', 'relief fund', 'humanitarian', 'tax deductible', 'nonprofit', 'fundraising',
        'благотворительность', 'пожертвование', 'помогите жертвам',
        'гуманитарная помощь', 'фонд помощи', 'сбор средств',
        'צדקה', 'תרומה', 'לתרום', 'קרן סיוע', 'עמותה', 'ארגון ללא מטרות רווח',
        'خيرية', 'تبرع', 'صندوق مساعدة', 'إغاثة', 'منظمة غير ربحية',
        'donación', 'caridad', 'víctimas', 'ayuda humanitaria', 'sin fines de lucro',
        'don', 'charité', 'victimes', 'aide humanitaire', 'organisation à but non lucratif'))

    # Features 27-31: Conversational behaviour (derived from transcript)
    features.append(_repetition_score(text_lower))                                        # 27: repeated phrases
    features.append(_question_density(text_lower))                                        # 28: question-word density
    features.append(_has_long_monologue(text))                                            # 29: uninterrupted monologue
    features.append(_urgency_escalates(text_lower))                                       # 30: urgency heavier in 2nd half
    features.append(_has_repeated_openers(text))                                          # 31: scripted sentence openers

    # Features 32-37: Audio/prosody + call metadata (0.0 for transcript-only training rows)
    features.append(min(avg_rms / 20.0, 1.0))                                            # 32: normalised avg loudness
    features.append(min(rms_std_dev / 5.0, 1.0))                                         # 33: normalised loudness variation
    features.append(min(silence_ratio, 1.0))                                              # 34: fraction of call silent
    features.append(float(had_long_silence))                                              # 35: had a scripted-pause silence
    features.append(min(call_duration_sec / 600.0, 1.0))                                  # 36: normalised duration (10 min max)
    features.append(1.0 if (call_hour < 8 or call_hour >= 20) else 0.0)                  # 37: off-hours call

    # Features 38-42: Call-centre / multi-speaker / DTMF signals (0.0 for transcript-only rows)
    call_dur_min = call_duration_sec / 60.0 if call_duration_sec > 0 else 0.0
    features.append(min(speaker_switches / call_dur_min, 1.0) if call_dur_min > 0 else 0.0)  # 38: speaker switches per minute
    features.append(min(noise_floor_db / 2.0, 1.0))                                      # 39: background noise floor normalised
    features.append(min(speech_rate_wpm / 300.0, 1.0))                                   # 40: speech rate normalised (300 wpm max)
    features.append(float(dtmf_detected))                                                 # 41: DTMF / IVR tones detected
    features.append(1.0 if noise_floor_db > 1.0 else 0.0)                                # 42: elevated background noise flag

    return features

def load_data(csv_path):
    """
    Load training data from CSV file.

    Minimum required columns:
        text,label

    Optional audio/metadata columns (default to 0 when absent):
        avg_rms, rms_std_dev, silence_ratio, had_long_silence,
        call_duration_sec, call_hour,
        speaker_switches, noise_floor_db, speech_rate_wpm, dtmf_detected

    Labels:
    0 - Legitimate call
    1 - IRS scam
    2 - Tech support scam
    3 - Bank fraud
    4 - Lottery prize scam
    5 - Social Security scam
    6 - Robocall
    7 - Phishing
    8 - Insurance scam
    9 - Investment scam
    10 - Donation fraud
    """
    df = pd.read_csv(csv_path)

    def row_features(row):
        return extract_features(
            text=row['text'],
            avg_rms=row.get('avg_rms', 0.0),
            rms_std_dev=row.get('rms_std_dev', 0.0),
            silence_ratio=row.get('silence_ratio', 0.0),
            had_long_silence=row.get('had_long_silence', 0.0),
            call_duration_sec=row.get('call_duration_sec', 0.0),
            call_hour=int(row.get('call_hour', 12)),
            speaker_switches=int(row.get('speaker_switches', 0)),
            noise_floor_db=float(row.get('noise_floor_db', 0.0)),
            speech_rate_wpm=float(row.get('speech_rate_wpm', 0.0)),
            dtmf_detected=float(row.get('dtmf_detected', 0.0)),
        )

    X = np.array([row_features(row) for _, row in df.iterrows()])
    y = np.array(df['label'])

    return X, y

def create_model():
    """Create the neural network model."""
    model = keras.Sequential([
        keras.layers.Dense(64, activation='relu', input_shape=(NUM_FEATURES,)),
        keras.layers.Dropout(0.3),
        keras.layers.Dense(32, activation='relu'),
        keras.layers.Dropout(0.3),
        keras.layers.Dense(NUM_CLASSES, activation='softmax')
    ])

    model.compile(
        optimizer='adam',
        loss='sparse_categorical_crossentropy',
        metrics=['accuracy']
    )

    return model

def train_model(X_train, y_train, X_val, y_val):
    """Train the model."""
    model = create_model()

    # Add callbacks
    callbacks = [
        keras.callbacks.EarlyStopping(
            monitor='val_loss',
            patience=10,
            restore_best_weights=True
        ),
        keras.callbacks.ReduceLROnPlateau(
            monitor='val_loss',
            factor=0.5,
            patience=5
        )
    ]

    # Train
    history = model.fit(
        X_train, y_train,
        epochs=EPOCHS,
        batch_size=BATCH_SIZE,
        validation_data=(X_val, y_val),
        callbacks=callbacks,
        verbose=1
    )

    return model, history

def convert_to_tflite(model, output_path):
    """Convert Keras model to TensorFlow Lite."""
    converter = tf.lite.TFLiteConverter.from_keras_model(model)

    # Optimize model
    converter.optimizations = [tf.lite.Optimize.DEFAULT]

    # Convert
    tflite_model = converter.convert()

    # Save
    with open(output_path, 'wb') as f:
        f.write(tflite_model)

    print(f"Model saved to {output_path}")
    print(f"Model size: {len(tflite_model) / 1024:.2f} KB")

def evaluate_model(model, X_test, y_test):
    """Evaluate model performance."""
    loss, accuracy = model.evaluate(X_test, y_test, verbose=0)
    print(f"\nTest Accuracy: {accuracy * 100:.2f}%")
    print(f"Test Loss: {loss:.4f}")

    # Per-class accuracy
    predictions = model.predict(X_test)
    predicted_classes = np.argmax(predictions, axis=1)

    class_names = [
        "Legitimate", "IRS", "Tech Support", "Bank Fraud",
        "Lottery", "Social Security", "Robocall",
        "Phishing", "Insurance", "Investment Scam", "Donation Fraud"
    ]

    print("\nPer-class accuracy:")
    for i in range(NUM_CLASSES):
        mask = y_test == i
        if mask.sum() > 0:
            class_acc = (predicted_classes[mask] == i).sum() / mask.sum()
            print(f"  {class_names[i]}: {class_acc * 100:.2f}%")

def main():
    """Main training pipeline."""
    print("VocaGuard Scam Detector Training")
    print("=" * 50)

    # Load data
    print("\n1. Loading data...")
    X, y = load_data('training_data.csv')
    print(f"   Loaded {len(X)} examples")
    print(f"   Features shape: {X.shape}")

    # Split data
    print("\n2. Splitting data...")
    X_train, X_temp, y_train, y_temp = train_test_split(
        X, y, test_size=0.3, random_state=42
    )
    X_val, X_test, y_val, y_test = train_test_split(
        X_temp, y_temp, test_size=0.5, random_state=42
    )

    print(f"   Training: {len(X_train)} examples")
    print(f"   Validation: {len(X_val)} examples")
    print(f"   Test: {len(X_test)} examples")

    # Features are already normalized ratios [0, 1] — no scaling needed.
    # The Android TextPreprocessor uses the same raw features, so scaling here
    # would cause a train/inference mismatch.

    # Train model
    print("\n4. Training model...")
    model, history = train_model(X_train, y_train, X_val, y_val)

    # Evaluate
    print("\n5. Evaluating model...")
    evaluate_model(model, X_test, y_test)

    # Convert to TensorFlow Lite
    print("\n6. Converting to TensorFlow Lite...")
    convert_to_tflite(model, 'scam_detector.tflite')

    print("\nTraining complete!")
    print("\nNext steps:")
    print("1. Copy scam_detector.tflite to app/src/main/assets/")
    print("2. Rebuild and install the app")
    print("3. Check logs for 'ML model loaded successfully'")

if __name__ == '__main__':
    main()