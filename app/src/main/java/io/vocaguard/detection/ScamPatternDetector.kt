package io.vocaguard.detection

import android.util.Log
import io.vocaguard.data.ScamType

class ScamPatternDetector() {

    companion object {
        private const val TAG = "ScamPatternDetector"
        private const val CONFIDENCE_THRESHOLD = 0.65f
        private const val MIN_KEYWORD_MATCHES = 3

        // Scam pattern keywords organized by type.
        // Only multi-word or highly specific phrases — single generic words removed to cut false positives.
        private val SCAM_PATTERNS = mapOf(
            ScamType.IRS_SCAM to listOf(
                // English
                "IRS", "internal revenue", "owe money", "arrest warrant",
                "legal action", "tax refund", "tax fraud", "tax lien", "back taxes",
                // Russian
                "налоговая служба", "налоговый долг", "задолженность по налогам", "фнс",
                // Hebrew
                "מס הכנסה", "רשות המסים", "חוב מס", "עיקול מס",
                // Arabic
                "مصلحة الضرائب", "ديون ضريبية", "مديونية ضريبية",
                // Spanish
                "servicio de impuestos", "deuda fiscal", "agencia tributaria", "hacienda pública",
                // French
                "service des impôts", "dette fiscale", "direction générale des finances publiques"
            ),
            ScamType.TECH_SUPPORT to listOf(
                // English
                "computer virus", "microsoft support", "windows support", "technical support",
                "your computer", "malware detected", "remote access", "teamviewer",
                "tech support", "apple support", "google support", "anydesk",
                "your device is infected", "suspicious activity on your computer",
                // Russian
                "техподдержка", "удалённый доступ", "ваш компьютер взломан", "заражён вирусом",
                // Hebrew
                "תמיכה טכנית", "גישה מרחוק", "תוכנה זדונית", "המחשב שלך נגוע",
                // Arabic
                "دعم فني", "وصول عن بعد", "برامج خبيثة", "جهازك مخترق",
                // Spanish
                "soporte técnico", "acceso remoto", "virus informático", "software malicioso",
                // French
                "support technique", "accès à distance", "virus informatique", "logiciel malveillant"
            ),
            ScamType.BANK_FRAUD to listOf(
                // English
                "verify your account", "unusual activity", "fraud alert",
                "unauthorized transaction", "account locked", "security breach",
                "confirm your identity", "anydesk", "your account has been",
                "suspicious transaction", "your card has been",
                // Russian
                "счёт заблокирован", "подозрительная активность", "несанкционированная операция",
                // Hebrew
                "חשבון חסום", "פעילות חשודה", "עסקה לא מאושרת", "אמת את זהותך",
                // Arabic
                "نشاط مشبوه", "معاملة غير مصرح بها", "حسابك محظور",
                // Spanish
                "actividad sospechosa", "fraude bancario", "cuenta bloqueada", "transacción no autorizada",
                // French
                "activité suspecte", "fraude bancaire", "compte bloqué", "transaction non autorisée"
            ),
            ScamType.LOTTERY_PRIZE to listOf(
                // English
                "lottery", "sweepstakes", "claim your prize", "free vacation",
                "free cruise", "cash prize", "you have been selected",
                "you are our winner", "collect your winnings",
                "you have won", "you've won", "won the prize", "won a prize",
                "your prize", "take your prize", "million dollar", "million dollars",
                "you are a winner", "profits are guaranteed",
                // Russian
                "лотерея", "вы выиграли приз", "получите выигрыш",
                "вы выиграли", "ваш приз", "миллион долларов",
                // Hebrew
                "הגרלה", "זכית בפרס", "תבע את הפרס שלך",
                "זכית", "הפרס שלך", "מיליון דולר",
                // Arabic
                "يانصيب", "فزت بجائزة", "استلم جائزتك",
                "جائزتك", "فزت في", "مليون دولار",
                // Spanish
                "lotería", "reclamar su premio", "usted ha ganado", "cobrar su premio",
                "ha ganado", "su premio", "un millón de dólares",
                // French
                "loterie", "réclamez votre prix", "vous avez gagné", "encaissez vos gains",
                "votre prix", "vous avez remporté", "un million de dollars"
            ),
            ScamType.SOCIAL_SECURITY to listOf(
                // English
                "social security", "SSN", "social security number",
                "illegal activity", "social security administration",
                "your SSN has been", "social security suspended",
                // Russian
                "страховой номер снилс", "пенсионный фонд заблокирован", "паспортные данные украдены",
                // Hebrew
                "מספר ביטוח לאומי", "ת.ז חסומה", "פרטים אישיים נגנבו",
                // Arabic
                "رقم الضمان الاجتماعي", "الهوية الوطنية محظورة",
                // Spanish
                "número de seguridad social", "actividad ilegal detectada", "documentos de identidad suspendidos",
                // French
                "numéro de sécurité sociale", "activité illégale détectée", "pièce d'identité suspendue"
            ),
            ScamType.ROBOCALL to listOf(
                // English
                "this is a recorded message", "do not hang up", "press 1",
                "call back immediately", "final notice", "this call may be recorded",
                "you have been selected", "respond to this message",
                // Russian
                "нажмите один", "нажмите 1", "это записанное сообщение",
                // Hebrew
                "לחץ 1", "הודעה מוקלטת", "הודעה אוטומטית",
                // Arabic
                "اضغط 1", "رسالة مسجلة", "لا تغلق الخط",
                // Spanish
                "presione 1", "mensaje grabado", "no cuelgue", "aviso final",
                // French
                "appuyez sur 1", "message enregistré", "ne raccrochez pas", "avis final"
            ),
            ScamType.PHISHING to listOf(
                // English
                "update your information", "account verification",
                "password reset", "click the link", "provide your details",
                "verify your identity", "confirm your account", "log in immediately",
                // Russian
                "обновите данные", "подтвердите свою личность", "войдите в систему немедленно",
                // Hebrew
                "עדכן פרטים", "אמת את זהותך", "לחץ כאן לאישור",
                // Arabic
                "تحديث معلوماتك", "تأكيد هويتك", "انقر هنا للتحقق",
                // Spanish
                "actualice su información", "verifique su identidad", "haga clic en el enlace",
                // French
                "mettez à jour vos informations", "vérifiez votre identité", "cliquez sur le lien"
            ),
            ScamType.INSURANCE to listOf(
                // English
                "health insurance", "medicare", "medicaid", "insurance plan",
                "free insurance", "limited time offer", "you qualify for coverage",
                "insurance expires", "your coverage will end",
                // Russian
                "медицинская страховка", "ваш полис истекает",
                // Hebrew
                "ביטוח בריאות", "הפוליסה שלך פגה", "כיסוי ביטוחי",
                // Arabic
                "تأمين صحي", "وثيقة تأمين", "تغطية تأمينية",
                // Spanish
                "seguro de salud", "cobertura médica", "póliza de seguro", "seguro médico",
                // French
                "assurance maladie", "assurance santé", "couverture médicale", "mutuelle"
            ),
            ScamType.INVESTMENT_SCAM to listOf(
                // English — social-media-ad referencing (calm first-call pattern)
                "saw your interest online", "left your details", "registered on our",
                "saw our advertisement", "saw our ad", "from the advertisement",
                "facebook ad", "instagram ad", "promoted post", "our promotion",
                "you filled out our form", "you signed up on our",
                "financial consultant", "investment consultant", "personal advisor",
                "your capital is protected", "capital is always protected",
                "no pressure", "no risk to your funds", "withdraw at any time",
                "managed account", "fully managed", "our experts handle",
                "consistent returns", "consistent profit", "monthly returns",
                "profits are guaranteed", "returns are guaranteed",
                // English — core investment fraud phrases
                "guaranteed returns", "high returns", "risk free", "double your money",
                "investment opportunity", "limited slots", "exclusive offer",
                "bitcoin investment", "trading platform", "passive income",
                "financial freedom", "get rich quick", "insider tip", "secret strategy",
                // Crypto / DeFi / token
                "crypto arbitrage", "defi staking", "nft project", "token presale",
                "ico launch", "altcoin listing", "binance listing", "ethereum staking",
                "usdt transfer", "mining pool", "connect your wallet", "approve the contract",
                "crypto signal", "vip trading group", "telegram signal group",
                // Pig-butchering / managed trading
                "account manager", "personal trader", "vip trading account",
                "trading balance", "withdraw your profits", "fund your account",
                "minimum deposit", "initial deposit", "trading robot", "ai trading",
                "forex robot", "automated trading", "managed account",
                // Withdrawal block tactics
                "tax clearance fee", "withdrawal fee", "unlock your withdrawal",
                "security deposit to withdraw", "upgrade your account tier",
                "processing fee to release", "insurance bond", "anti-money laundering fee",
                // Pump and dump / Ponzi
                "recruit members", "downline", "network marketing investment",
                "peer to peer lending", "profit sharing cooperative",
                "earn commissions on referrals", "build your downline",
                // Russian
                "гарантированный доход", "криптовалютные инвестиции", "пассивный доход без риска",
                "торговый робот", "личный брокер", "вывод прибыли", "пополнить счёт",
                "сигналы форекс", "vip торговый аккаунт", "арбитраж криптовалюты",
                // Hebrew — social-media-ad referencing pattern
                "השארת פרטים", "ראיתי שהתעניינת", "נרשמת דרך הפרסומת",
                "פרסומת בפייסבוק", "פרסומת באינסטגרם", "ברשתות החברתיות",
                "יועץ פיננסי אישי", "יועץ השקעות", "חשבון מנוהל",
                "ההון שלך מוגן", "אין סיכון לכסף", "למשוך בכל עת",
                "תשואה חודשית קבועה", "רווחים מובטחים", "אין לחץ",
                "המומחים שלנו מטפלים", "הכנסה פסיבית ללא ניסיון",
                // Hebrew — core
                "תשואה מובטחת", "השקעה בקריפטו", "הכפלת כסף", "פורקס",
                "מנהל חשבון", "רובוט מסחר", "משיכת רווחים", "הפקדה ראשונית",
                "אות מסחר", "קבוצת טלגרם vip", "ארביטראז' קריפטו",
                // Arabic
                "عائد مضمون", "استثمار بعملة مشفرة", "مضاعفة الأموال",
                "روبوت التداول", "مدير الحساب", "سحب الأرباح", "تمويل الحساب",
                "إيداع أولي", "مجموعة إشارات vip", "تداول مؤتمت",
                // Spanish
                "inversión garantizada", "ganancias altas sin riesgo", "criptomoneda",
                "gestor de cuenta", "robot de trading", "retiro de ganancias",
                "depósito mínimo", "grupo de señales vip", "trading automatizado",
                // French
                "investissement garanti", "rendements élevés sans risque", "cryptomonnaie",
                "gestionnaire de compte", "robot de trading", "retrait des bénéfices",
                "dépôt initial", "groupe de signaux vip", "trading automatisé"
            ),
            ScamType.DONATION_FRAUD to listOf(
                // English
                "help victims", "disaster relief", "make a contribution",
                "support our cause", "relief fund", "tax deductible donation",
                "donate now to help", "emergency fundraising",
                // Russian
                "гуманитарная помощь пострадавшим", "пожертвуйте сейчас",
                // Hebrew
                "קרן סיוע לנפגעים", "לתרום עכשיו", "עמותה לסיוע",
                // Arabic
                "إغاثة المتضررين", "تبرع الآن", "منظمة إغاثة",
                // Spanish
                "ayuda a las víctimas", "donación de emergencia", "fondo de socorro",
                // French
                "aide aux victimes", "don d'urgence", "fonds de secours"
            ),

            // ── Social Engineering ────────────────────────────────────────────────
            // Calm, professional impersonation scams: "safe account" transfers,
            // fake authority (bank fraud dept, federal agents), secrecy demands,
            // and step-by-step guidance toward dangerous actions.
            ScamType.SOCIAL_ENGINEERING to listOf(
                // English — "safe account" / fund-transfer tactics
                "safe account", "protected account", "security account",
                "move your funds", "transfer your savings", "withdraw your savings",
                "move your money to safety", "transfer to a safe",
                // English — fake authority / impersonation
                "fraud department", "federal investigation", "under investigation",
                "you are being investigated", "I can give you my badge number",
                "I'll give you my employee ID", "we have caught the criminal",
                "you are the victim here", "we are protecting your funds",
                "your identity has been stolen", "we are trying to protect you",
                // English — secrecy / isolation demands (strongest signal)
                "do not tell anyone", "don't tell anyone",
                "keep this confidential", "don't discuss this",
                "keep this between us", "do not contact your bank",
                "don't call the police", "do not call anyone",
                "this is a private investigation",
                // English — step-by-step guidance tactics
                "stay on the line with me", "I'll walk you through this",
                "scratch the numbers on the back", "read me the numbers",
                "gift card to protect", "buy gift cards for security",
                // Russian
                "безопасный счёт", "никому не рассказывайте", "переведите деньги",
                "мы пытаемся вас защитить", "ваша личность похищена",
                "федеральное расследование", "держите в тайне", "не звоните в полицию",
                // Hebrew
                "חשבון בטוח", "אל תספר לאף אחד", "שמור בסוד",
                "חקירה פדרלית", "העבר את הכסף", "אנחנו מגנים עליך",
                "הזהות שלך נגנבה", "שמור על סודיות", "אל תתקשר למשטרה",
                // Arabic
                "حساب آمن", "لا تخبر أحداً", "قيد التحقيق",
                "حوّل أموالك", "نحن نحاول حمايتك", "هويتك مسروقة",
                "اكتم هذا الأمر", "لا تتصل بالشرطة",
                // Spanish
                "cuenta segura", "no se lo diga a nadie", "bajo investigación",
                "transfiera sus fondos", "estamos intentando protegerle",
                "su identidad ha sido robada", "mantenga esto en secreto",
                "no llame a la policía",
                // French
                "compte sécurisé", "n'en parlez à personne", "sous enquête",
                "transférez vos fonds", "nous essayons de vous protéger",
                "votre identité a été volée", "gardez cela confidentiel",
                "n'appelez pas la police"
            ),

            // ── Romance Scam ──────────────────────────────────────────────────────
            ScamType.ROMANCE_SCAM to listOf(
                // English
                "I am stranded", "stranded abroad", "stuck overseas", "stuck abroad",
                "military deployment", "military service overseas",
                "send me money", "I need money urgently", "wire me the money",
                "medical emergency abroad", "cannot access my funds",
                "visa problem", "customs fee", "release fee",
                "I will pay you back", "I just need this one time",
                // Russian
                "я застрял за границей", "военная командировка",
                "пришли мне деньги", "срочно нужны деньги",
                // Hebrew
                "תקוע בחו\"ל", "שלח לי כסף", "צריך כסף דחוף", "פריסה צבאית",
                // Arabic
                "عالق في الخارج", "أرسل لي مالاً", "أحتاج مالاً بشكل عاجل",
                "نشر عسكري",
                // Spanish
                "atrapado en el extranjero", "envíame dinero",
                "necesito dinero urgente", "despliegue militar",
                // French
                "coincé à l'étranger", "envoyez-moi de l'argent",
                "besoin d'argent urgent", "déploiement militaire"
            ),

            // ── Delivery Scam ─────────────────────────────────────────────────────
            ScamType.DELIVERY_SCAM to listOf(
                // English
                "package on hold", "shipment held", "customs clearance fee",
                "delivery fee required", "pay to release your package",
                "failed delivery attempt", "redelivery fee",
                "your parcel is held", "reschedule your delivery",
                "pay customs duty",
                // Russian
                "посылка задержана", "оплатите доставку", "таможенный сбор",
                // Hebrew
                "חבילה עצורה", "תשלום למכס", "דמי שחרור", "משלוח עצור",
                // Arabic
                "الطرد محتجز", "رسوم جمركية", "رسوم الاستلام",
                // Spanish
                "paquete retenido", "arancel aduanero",
                "tasa de entrega", "intento de entrega fallido",
                // French
                "colis retenu", "droits de douane",
                "frais de livraison", "tentative de livraison échouée"
            ),

            // ── Job Scam ──────────────────────────────────────────────────────────
            ScamType.JOB_SCAM to listOf(
                // English
                "work from home opportunity", "earn from home",
                "no experience required", "training fee", "registration fee",
                "starter kit fee", "process payments for us",
                "keep a commission", "package reshipping",
                "mystery shopper", "guaranteed daily earnings",
                "be your own boss", "easy money from home",
                // Russian
                "работа из дома без опыта", "регистрационный взнос",
                "обработка платежей", "ежедневный заработок гарантирован",
                // Hebrew
                "עבודה מהבית", "דמי הרשמה", "הכנסה יומית מובטחת",
                "לא נדרש ניסיון",
                // Arabic
                "عمل من المنزل", "رسوم تسجيل",
                "دخل يومي مضمون", "لا يلزم خبرة",
                // Spanish
                "trabajar desde casa", "tarifa de registro",
                "ingresos diarios garantizados", "sin experiencia necesaria",
                // French
                "travailler depuis chez soi", "frais d'inscription",
                "revenus quotidiens garantis", "sans expérience requise"
            )
        )

        // High-priority urgent language patterns.
        // Single generic words ("urgent", "now") removed — they appear in normal conversation
        // and only become meaningful when already surrounded by 3+ scam-type keywords.
        private val URGENCY_KEYWORDS = listOf(
            // English
            "immediately", "right now", "within 24 hours",
            "today only", "expires today", "last chance", "final notice",
            "act now", "time sensitive", "limited time",
            // Russian
            "срочно", "немедленно", "последний шанс", "действуйте сейчас",
            // Hebrew
            "דחוף", "מיד", "הזדמנות אחרונה", "תוך 24 שעות",
            // Arabic
            "عاجل", "فوراً", "فرصة أخيرة", "خلال 24 ساعة",
            // Spanish
            "urgente", "inmediatamente", "ahora mismo", "última oportunidad", "solo hoy", "tiempo limitado",
            // French
            "immédiatement", "maintenant", "dernière chance", "aujourd'hui seulement", "temps limité"
        )

        // Threat/pressure keywords.
        // Generic English words ("police", "cancelled", "locked", "frozen", "suspended") removed —
        // they appear routinely in news, notifications, and normal chat. Only terms that are
        // rarely used outside a threat context are kept.
        private val THREAT_KEYWORDS = listOf(
            // English
            "arrest", "lawsuit", "legal action", "seized", "warrant",
            // Russian
            "арест", "полиция", "уголовное дело", "заблокирован", "заморожен",
            // Hebrew
            "מעצר", "משטרה", "תיק פלילי", "חסום", "מוקפא", "עיקול",
            // Arabic
            "اعتقال", "شرطة", "قضية جنائية", "محظور", "مجمد",
            // Spanish
            "arresto", "policía", "demanda", "acción legal", "suspendido", "congelado", "orden de arresto",
            // French
            "arrestation", "police", "poursuites judiciaires", "suspendu", "gelé", "mandat d'arrêt"
        )

        // Secrecy / isolation demands — almost never made by a legitimate caller.
        // Even one instance alongside any scam keyword is a very strong signal.
        private val SECRECY_KEYWORDS = listOf(
            // English
            "do not tell anyone", "don't tell anyone",
            "keep this confidential", "keep this between us",
            "don't discuss this with anyone", "this is confidential",
            "do not contact your bank", "don't call the police",
            "do not call anyone else", "this is a private investigation",
            // Russian
            "никому не говорите", "держите в тайне", "не звоните в полицию",
            // Hebrew
            "אל תספר לאף אחד", "שמור על סודיות", "אל תתקשר למשטרה",
            // Arabic
            "لا تخبر أحداً", "اكتم هذا الأمر", "لا تتصل بالشرطة",
            // Spanish
            "no se lo diga a nadie", "mantenga esto en secreto",
            "no llame a la policía",
            // French
            "n'en parlez à personne", "gardez cela confidentiel",
            "n'appelez pas la police"
        )

        // Social-media-ad investment scam phrases.
        // Calm first-call pattern: scammer references a Facebook/Instagram ad, presents as a
        // financial consultant, and emphasises capital protection — no urgency or threats.
        private val SOCIAL_AD_PHRASES = listOf(
            // English — ad reference
            "facebook", "instagram", "social media", "our advertisement", "our ad",
            "our promotion", "promoted post", "you registered", "you signed up",
            "left your details", "filled out our form", "our website",
            // English — role / trust language
            "financial consultant", "investment consultant", "personal advisor",
            "personal financial", "your dedicated", "dedicated consultant",
            // English — capital protection / calm pitch
            "capital is protected", "capital is always", "no pressure",
            "no risk to your", "withdraw at any time", "your funds are safe",
            "consistent returns", "monthly returns", "managed account",
            // Hebrew
            "פייסבוק", "אינסטגרם", "רשתות חברתיות", "הפרסומת שלנו",
            "נרשמת", "השארת פרטים", "מילאת את הטופס",
            "יועץ פיננסי", "יועץ השקעות", "יועץ אישי",
            "ההון שלך מוגן", "אין לחץ", "למשוך בכל עת",
            "תשואה חודשית", "חשבון מנוהל",
            // Russian
            "фейсбук", "инстаграм", "наша реклама", "вы зарегистрировались",
            "оставили данные", "финансовый консультант", "личный советник",
            "ваш капитал защищён", "без давления", "ежемесячная доходность",
            // Arabic
            "فيسبوك", "إنستغرام", "إعلاننا", "سجلت اهتمامك",
            "تركت بياناتك", "مستشار مالي", "رأس مالك محمي",
            "لا ضغط", "عائد شهري",
            // Spanish
            "facebook", "instagram", "nuestro anuncio", "se registró",
            "dejó sus datos", "consultor financiero", "capital protegido",
            "sin presión", "rendimiento mensual",
            // French
            "facebook", "instagram", "notre annonce", "vous êtes inscrit",
            "laissé vos coordonnées", "conseiller financier", "capital protégé",
            "sans pression", "rendement mensuel"
        )

        // Payment request keywords.
        private val PAYMENT_KEYWORDS = listOf(
            // English
            "gift card", "bitcoin", "wire transfer", "cash", "prepaid card",
            "money order", "western union", "moneygram", "zelle", "venmo", "cashapp",
            "pay now", "payment required", "send money",
            // Russian
            "подарочная карта", "биткоин", "перевод средств", "вестерн юнион",
            // Hebrew
            "גיפט קארד", "ביטקוין", "העברה בנקאית", "ביט", "פייבוקס",
            // Arabic
            "بطاقة هدية", "بيتكوين", "تحويل مالي", "ويسترن يونيون",
            // Spanish
            "tarjeta de regalo", "transferencia bancaria", "western union", "pague ahora",
            // French
            "carte cadeau", "virement bancaire", "western union", "payez maintenant"
        )

        // Personal info request keywords
        private val INFO_REQUEST_KEYWORDS = listOf(
            // English
            "social security number", "SSN", "date of birth", "mother's maiden name",
            "bank account number", "credit card number", "PIN", "password",
            "routing number", "account number",
            // Russian
            "пароль", "пин-код", "номер счёта", "паспортные данные",
            // Hebrew
            "סיסמה", "פין", "מספר חשבון", "תעודת זהות",
            // Arabic
            "كلمة المرور", "رقم الحساب", "بطاقة هوية",
            // Spanish
            "número de seguridad social", "contraseña", "número de cuenta", "fecha de nacimiento", "pin",
            // French
            "numéro de sécurité sociale", "mot de passe", "numéro de compte", "date de naissance", "code pin"
        )

        // ── Message-specific detection ─────────────────────────────────────────
        // All entries are multi-word phrases. Single generic words are excluded to
        // eliminate false positives from normal chat about crypto, stocks, or finance.

        private const val MIN_MESSAGE_PHRASE_MATCHES = 2
        private const val MESSAGE_CONFIDENCE_PER_PHRASE = 0.35f
        private const val CRITICAL_PHRASE_CONFIDENCE = 0.92f
        private const val MESSAGE_THRESHOLD = 0.65f

        /**
         * Single-match critical phrases. These are virtually never used in legitimate
         * conversation — one match is sufficient to flag a scam with high confidence.
         */
        private val CRITICAL_PHRASES = setOf(
            // Crypto wallet drainer (seed / private key requests)
            "seed phrase", "recovery phrase", "wallet seed", "secret phrase",
            "secret recovery phrase", "wallet passphrase", "wallet recovery",
            "12-word phrase", "24-word phrase", "12 word phrase", "24 word phrase",
            "private key", "wallet private", "export your wallet",
            // Hebrew
            "ביטוי שחזור", "מפתח פרטי", "גרעין הארנק", "ביטוי סודי",
            // Russian
            "фраза восстановления", "приватный ключ", "сид-фраза", "сид фраза",
            // Arabic
            "عبارة الاسترداد", "المفتاح الخاص", "عبارة البذرة"
        )

        /**
         * Message-specific phrase patterns — used only when scanning text messages.
         * All entries are 2+ word phrases that rarely appear in legitimate chat.
         */
        private val MESSAGE_PATTERNS = mapOf(

            // ── Investment / crypto / trading / stock ──────────────────────────
            ScamType.INVESTMENT_SCAM to listOf(
                // Crypto wallet operations (drainer / approval scams)
                "connect your wallet", "sync your wallet", "validate your wallet",
                "reconnect your wallet", "approve the transaction", "approve this transaction",
                "sign this transaction", "connect wallet", "wallet connect",
                "send usdt", "send btc", "send ethereum", "send crypto",
                "usdt transfer", "transfer usdt", "transfer btc",
                // Pig-butchering / managed trading
                "trading platform", "account manager", "personal trader",
                "trading balance", "trading profits", "withdraw your profits",
                "fund your account", "minimum deposit", "initial deposit",
                "trading robot", "ai trading", "ai trading bot",
                "automated trading", "managed account", "let me teach you to trade",
                "trading group", "vip trading group", "join my trading",
                "my mentor can", "my broker can", "my account manager",
                "portfolio has grown", "your balance has grown",
                // Withdrawal block / fee extortion (strongest signal after critical phrases)
                "withdrawal fee", "tax clearance fee", "fee to withdraw",
                "fee to release", "processing fee to release", "unlock your withdrawal",
                "insurance bond", "anti-money laundering fee",
                "upgrade your account tier", "tax to withdraw",
                "security deposit to withdraw", "release your funds",
                // Signal groups
                "crypto signals", "forex signals", "trading signals", "vip signals",
                "crypto signal group", "telegram signal",
                // DeFi / token / NFT
                "token presale", "token pre-sale", "ico launch", "nft project",
                "defi staking", "liquidity pool", "yield farming",
                "mining pool", "cloud mining", "mining contract",
                "altcoin listing", "binance listing", "coinbase listing",
                // Stock / crypto pump-and-dump
                "hot stock tip", "penny stock", "stock alert",
                "insider tip", "buy before it", "get in early",
                "before it moons", "before it pumps", "guaranteed to moon",
                "will 10x", "will 100x", "before listing", "pre-listing",
                "early investor", "early access to", "whitelist spot",
                "this coin will", "this stock will",
                // Guaranteed-return language
                "guaranteed returns", "guaranteed profit", "guaranteed income",
                "guaranteed daily", "risk free investment", "double your money",
                "passive income", "financial freedom", "get rich quick",
                "100% profit", "1000% return",
                // Hebrew
                "תשואה מובטחת", "קבוצת מסחר vip", "מנהל חשבון",
                "דמי משיכה", "דמי שחרור", "עמלת עיבוד",
                "הכפלת כסף", "רובוט מסחר", "ארביטראז' קריפטו",
                "אות מסחר", "פלטפורמת מסחר", "הפקדה ראשונית",
                "קבוצת סיגנלים", "לפני ההנפקה", "קבלת גישה מוקדמת",
                "השקעה מובטחת", "הכנסה פסיבית", "חופש פיננסי",
                // Russian
                "гарантированный доход", "торговая группа vip", "мой менеджер счёта",
                "вывод прибыли", "комиссия за вывод", "сбор за обработку",
                "торговый робот", "минимальный депозит", "торговая платформа",
                "до листинга", "ранний инвестор", "криптосигналы",
                "пассивный доход", "финансовая свобода", "удвоить деньги",
                // Arabic
                "عائد مضمون", "مجموعة vip للتداول", "مدير حسابي",
                "رسوم السحب", "رسوم التحرير", "رسوم المعالجة",
                "ربح مضمون", "استثمار مضمون", "منصة التداول",
                "قبل الإدراج", "مستثمر مبكر", "إشارات العملات",
                "دخل سلبي", "حرية مالية", "مضاعفة المال"
            ),

            // ── Phishing ──────────────────────────────────────────────────────
            ScamType.PHISHING to listOf(
                "click this link", "click the link", "verify your account",
                "account verification", "confirm your details",
                "your account will be closed", "update your payment",
                "confirm your password", "log in to avoid",
                "suspicious login", "verify your identity",
                "confirm your information", "unusual sign-in",
                "account suspended", "account will be terminated",
                "one-time password", "enter your otp",
                // Hebrew
                "לחץ כאן לאישור", "עדכן פרטי תשלום", "חשבונך ייחסם",
                "אמת זהותך", "כניסה חשודה", "אמת את החשבון",
                // Russian
                "нажмите здесь для подтверждения", "обновите платёжные данные",
                "подозрительный вход", "учётная запись заблокирована",
                "подтвердите свою личность",
                // Arabic
                "انقر هنا للتحقق", "تحديث بيانات الدفع",
                "تسجيل دخول مشبوه", "تأكيد هويتك"
            ),

            // ── Romance scam ──────────────────────────────────────────────────
            ScamType.ROMANCE_SCAM to listOf(
                "send me money", "wire me the money", "I need money urgently",
                "stranded abroad", "stuck overseas", "medical emergency abroad",
                "customs release fee", "visa problem", "I'll pay you back",
                "military deployment", "army base", "peacekeeping mission",
                "let me teach you to invest", "my uncle is a broker",
                "never felt this way", "fell in love with you",
                "I need your help urgently",
                // Hebrew
                "שלח לי כסף", "תקוע בחו\"ל", "צריך כסף דחוף",
                "פריסה צבאית", "אשלם לך בחזרה", "עזרה דחופה",
                // Russian
                "пришли мне деньги", "застрял за границей",
                "срочно нужны деньги", "военная командировка",
                // Arabic
                "أرسل لي مالاً", "عالق في الخارج",
                "أحتاج مالاً بشكل عاجل", "نشر عسكري"
            ),

            // ── Job scam ──────────────────────────────────────────────────────
            ScamType.JOB_SCAM to listOf(
                "work from home", "earn from home", "no experience required",
                "guaranteed daily earnings", "registration fee", "training fee",
                "starter kit fee", "process payments for us",
                "package reshipping", "mystery shopper",
                "easy money from home", "be your own boss",
                // Hebrew
                "עבודה מהבית", "הכנסה יומית מובטחת", "דמי הרשמה",
                "לא נדרש ניסיון", "עמלה קבועה",
                // Russian
                "работа из дома", "регистрационный взнос",
                "ежедневный заработок гарантирован", "без опыта работы",
                // Arabic
                "عمل من المنزل", "رسوم تسجيل",
                "دخل يومي مضمون", "لا يلزم خبرة"
            ),

            // ── Delivery scam ─────────────────────────────────────────────────
            ScamType.DELIVERY_SCAM to listOf(
                "package on hold", "customs clearance fee",
                "pay to release your package", "delivery fee required",
                "your parcel is held", "reschedule your delivery",
                "pay customs duty", "failed delivery attempt",
                "redelivery fee", "shipment held",
                // Hebrew
                "חבילה עצורה", "תשלום למכס", "דמי שחרור", "משלוח עצור",
                // Russian
                "посылка задержана", "таможенный сбор", "оплатите доставку",
                // Arabic
                "الطرد محتجز", "رسوم جمركية", "رسوم الاستلام"
            ),

            // ── Bank fraud ────────────────────────────────────────────────────
            ScamType.BANK_FRAUD to listOf(
                "unusual activity on your account", "unauthorized transaction",
                "account has been locked", "security breach detected",
                "confirm your account details", "fraud department",
                "verify your card", "your card has been suspended",
                "suspicious transaction", "your account has been",
                // Hebrew
                "פעילות חשודה בחשבון", "עסקה לא מאושרת",
                "אמת פרטי כרטיס", "חשבון חסום",
                // Russian
                "несанкционированная операция", "счёт заблокирован",
                "подозрительная активность", "отдел по борьбе с мошенничеством",
                // Arabic
                "نشاط مشبوه", "معاملة غير مصرح بها", "حسابك محظور"
            ),

            // ── Social engineering ─────────────────────────────────────────────
            ScamType.SOCIAL_ENGINEERING to listOf(
                "do not tell anyone", "don't tell anyone",
                "keep this confidential", "keep this between us",
                "move your funds", "transfer your savings",
                "safe account", "protected account",
                "don't contact your bank", "don't call the police",
                "federal investigation", "under investigation",
                "we are trying to protect you", "your identity has been stolen",
                "move your money to safety", "we are protecting your funds",
                // Hebrew
                "אל תספר לאף אחד", "חשבון בטוח", "העבר כספים",
                "שמור על סודיות", "חקירה פדרלית", "מגינים עליך",
                // Russian
                "никому не говорите", "безопасный счёт", "переведите деньги",
                "держите в тайне", "федеральное расследование",
                // Arabic
                "لا تخبر أحداً", "حساب آمن", "حوّل أموالك",
                "اكتم هذا الأمر", "قيد التحقيق"
            ),

            // ── Lottery / prize ───────────────────────────────────────────────
            ScamType.LOTTERY_PRIZE to listOf(
                "you have won", "you've won", "claim your prize",
                "collect your winnings", "you are our winner",
                "cash prize", "free vacation", "free cruise",
                "you have been selected", "million dollar",
                "prize money", "won the lottery",
                // Hebrew
                "זכית בפרס", "תבע את הפרס שלך", "מיליון דולר",
                "נבחרת לקבל", "כסף פרס",
                // Russian
                "вы выиграли", "ваш приз", "получите выигрыш",
                "миллион долларов", "вы стали победителем",
                // Arabic
                "فزت بجائزة", "استلم جائزتك", "مليون دولار", "تم اختيارك"
            ),

            // ── IRS / tax ─────────────────────────────────────────────────────
            ScamType.IRS_SCAM to listOf(
                "owe back taxes", "arrest warrant", "tax lien",
                "internal revenue", "legal action will be taken",
                "tax fraud investigation", "owe taxes",
                "back taxes", "tax refund claim",
                // Hebrew
                "חוב מס", "מס הכנסה", "עיקול מס", "חוב מסים",
                // Russian
                "налоговый долг", "задолженность по налогам", "налоговая служба",
                // Arabic
                "ديون ضريبية", "مصلحة الضرائب", "مديونية ضريبية"
            ),

            // ── Social security ────────────────────────────────────────────────
            ScamType.SOCIAL_SECURITY to listOf(
                "social security number", "SSN suspended", "social security suspended",
                "illegal activity on your social security",
                "social security administration",
                // Hebrew
                "מספר ביטוח לאומי", "ת.ז חסומה", "ביטוח לאומי נחסם",
                // Russian
                "страховой номер снилс", "пенсионный фонд заблокирован",
                // Arabic
                "رقم الضمان الاجتماعي", "الضمان الاجتماعي معلق"
            ),

            // ── Tech support ──────────────────────────────────────────────────
            ScamType.TECH_SUPPORT to listOf(
                "your computer has", "computer virus", "microsoft support",
                "remote access", "your device is infected",
                "suspicious activity on your computer", "anydesk", "teamviewer",
                "malware detected", "tech support", "apple support",
                // Hebrew
                "המחשב שלך נגוע", "גישה מרחוק", "תמיכה טכנית",
                // Russian
                "ваш компьютер взломан", "удалённый доступ", "техподдержка",
                // Arabic
                "جهازك مخترق", "وصول عن بعد", "دعم فني"
            )
        )
    }

    /**
     * Returns true if [text] contains [keyword] as a whole word (word-boundary match,
     * case-insensitive). Prevents false positives like "bitcoin" inside "habitcoin".
     */
    private fun containsWord(text: String, keyword: String): Boolean {
        val pattern = "(?<![\\w])${Regex.escape(keyword.lowercase())}(?![\\w])"
        return Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(text)
    }

    fun analyzeText(text: String): DetectionResult {
        val lowerText = text.lowercase()

        // Boost signals — computed once but only applied when keywords are matched (boost guard)
        val hasUrgency  = URGENCY_KEYWORDS.any  { containsWord(lowerText, it) }
        val hasThreat   = THREAT_KEYWORDS.any   { containsWord(lowerText, it) }
        val hasPayment  = PAYMENT_KEYWORDS.any  { containsWord(lowerText, it) }
        val hasInfo     = INFO_REQUEST_KEYWORDS.any { containsWord(lowerText, it) }
        // Secrecy demands: legitimate callers never ask you to hide the call from others.
        val hasSecrecy  = SECRECY_KEYWORDS.any  { containsWord(lowerText, it) }
        // Social-media-ad reference: scammer mentions Facebook/Instagram ad, financial consultant role,
        // or capital protection — hallmarks of the calm first-call investment scam.
        val hasSocialAdRef = SOCIAL_AD_PHRASES.any { containsWord(lowerText, it) }

        // Score every scam type and keep the best match
        var bestType: ScamType = ScamType.UNKNOWN
        var bestConfidence = 0f
        var bestKeywords: List<String> = emptyList()

        for ((scamType, keywords) in SCAM_PATTERNS) {
            val matched = keywords.filter { containsWord(lowerText, it) }

            // Boost guard: require at least MIN_KEYWORD_MATCHES before boosts apply
            if (matched.size < MIN_KEYWORD_MATCHES) continue

            val keywordScore = (matched.size * 0.12f).coerceAtMost(0.60f)

            // Boosts only fire because keywords already matched — no free boost from tone alone
            val urgencyBoost  = if (hasUrgency)  0.15f else 0f
            val threatBoost   = if (hasThreat)   0.20f else 0f
            val paymentBoost  = if (hasPayment)  0.15f else 0f
            val infoBoost     = if (hasInfo)     0.10f else 0f
            // Secrecy demand: unusually strong signal — legitimate callers never ask for silence
            val secrecyBoost  = if (hasSecrecy)  0.25f else 0f
            // Social-media-ad investment scam: calm first-call pattern — no urgency/threat/payment,
            // but references a Facebook/Instagram ad, financial consultant role, or capital protection.
            // Only applies to INVESTMENT_SCAM to stay precise.
            val socialAdBoost = if (scamType == ScamType.INVESTMENT_SCAM && hasSocialAdRef) 0.30f else 0f

            val confidence = (keywordScore + urgencyBoost + threatBoost + paymentBoost + infoBoost + secrecyBoost + socialAdBoost)
                .coerceAtMost(1.0f)

            if (confidence > bestConfidence) {
                bestConfidence = confidence
                bestType = scamType
                bestKeywords = matched
            }
        }

        if (bestConfidence >= CONFIDENCE_THRESHOLD) {
            Log.w(TAG, "Scam detected: $bestType (confidence: $bestConfidence)")
            return DetectionResult(
                isScam = true,
                scamType = bestType,
                confidence = bestConfidence,
                reason = "Detected ${bestKeywords.size} scam keywords: ${bestKeywords.take(3).joinToString(", ")}",
                keywords = bestKeywords
            )
        }

        // Last resort: all 4 signal categories must be simultaneously present
        val suspiciousScore = calculateSuspiciousScore(lowerText)
        if (suspiciousScore >= CONFIDENCE_THRESHOLD) {
            return DetectionResult(
                isScam = true,
                scamType = ScamType.UNKNOWN,
                confidence = suspiciousScore,
                reason = "Multiple simultaneous scam signals detected"
            )
        }

        return DetectionResult(
            isScam = false,
            scamType = ScamType.UNKNOWN,
            confidence = 0f,
            reason = "No scam patterns detected"
        )
    }

    /**
     * Analyses a text message (WhatsApp / Telegram / Messenger) for scam patterns.
     *
     * Uses [MESSAGE_PATTERNS] — phrase-only entries that are highly specific to scam
     * scripts — and [CRITICAL_PHRASES] which trigger on a single match alone.
     * Requires [MIN_MESSAGE_PHRASE_MATCHES] matches before scoring begins, so generic
     * crypto/finance discussion does not trigger false positives.
     */
    fun analyzeMessage(text: String): DetectionResult {
        val lowerText = text.lowercase()

        // Critical phrase check — one match is enough (virtually never legit).
        val criticalMatch = CRITICAL_PHRASES.firstOrNull { containsWord(lowerText, it) }
        if (criticalMatch != null) {
            return DetectionResult(
                isScam = true,
                scamType = ScamType.INVESTMENT_SCAM,
                confidence = CRITICAL_PHRASE_CONFIDENCE,
                reason = "Critical scam phrase detected: \"$criticalMatch\"",
                keywords = listOf(criticalMatch)
            )
        }

        // Boost signals (same as call detection — provide score boost when present alongside
        // phrase matches, but cannot trigger a result on their own).
        val hasUrgency  = URGENCY_KEYWORDS.any  { containsWord(lowerText, it) }
        val hasThreat   = THREAT_KEYWORDS.any   { containsWord(lowerText, it) }
        val hasPayment  = PAYMENT_KEYWORDS.any  { containsWord(lowerText, it) }
        val hasInfo     = INFO_REQUEST_KEYWORDS.any { containsWord(lowerText, it) }
        val hasSecrecy  = SECRECY_KEYWORDS.any  { containsWord(lowerText, it) }

        var bestType: ScamType = ScamType.UNKNOWN
        var bestConfidence = 0f
        var bestKeywords: List<String> = emptyList()

        for ((scamType, phrases) in MESSAGE_PATTERNS) {
            val matched = phrases.filter { containsWord(lowerText, it) }
            if (matched.size < MIN_MESSAGE_PHRASE_MATCHES) continue

            val phraseScore   = (matched.size * MESSAGE_CONFIDENCE_PER_PHRASE).coerceAtMost(0.75f)
            val urgencyBoost  = if (hasUrgency)  0.10f else 0f
            val threatBoost   = if (hasThreat)   0.12f else 0f
            val paymentBoost  = if (hasPayment)  0.10f else 0f
            val infoBoost     = if (hasInfo)     0.08f else 0f
            val secrecyBoost  = if (hasSecrecy)  0.18f else 0f

            val confidence = (phraseScore + urgencyBoost + threatBoost + paymentBoost +
                    infoBoost + secrecyBoost).coerceAtMost(1.0f)

            if (confidence > bestConfidence) {
                bestConfidence = confidence
                bestType = scamType
                bestKeywords = matched
            }
        }

        if (bestConfidence >= MESSAGE_THRESHOLD) {
            Log.w(TAG, "Scam message detected: $bestType (confidence=$bestConfidence)")
            return DetectionResult(
                isScam = true,
                scamType = bestType,
                confidence = bestConfidence,
                reason = "Scam message — matched: ${bestKeywords.take(3).joinToString(", ")}",
                keywords = bestKeywords
            )
        }

        return DetectionResult(
            isScam = false,
            scamType = ScamType.UNKNOWN,
            confidence = bestConfidence,
            reason = "No scam message patterns detected"
        )
    }

    private fun calculateSuspiciousScore(text: String): Float {
        val urgencyCount  = URGENCY_KEYWORDS.count       { containsWord(text, it) }
        val threatCount   = THREAT_KEYWORDS.count        { containsWord(text, it) }
        val paymentCount  = PAYMENT_KEYWORDS.count       { containsWord(text, it) }
        val infoCount     = INFO_REQUEST_KEYWORDS.count  { containsWord(text, it) }
        val secrecyCount  = SECRECY_KEYWORDS.count       { containsWord(text, it) }

        val categoriesPresent = listOf(urgencyCount, threatCount, paymentCount, infoCount)
            .count { it > 0 }

        // A secrecy demand lowers the bar from 4 signal categories to 3 — isolation is
        // a defining tactic of social-engineering scams even when urgency/threat is absent.
        val required = if (secrecyCount > 0) 3 else 4
        if (categoriesPresent < required) return 0f

        var score = 0f
        score += urgencyCount  * 0.12f
        score += threatCount   * 0.18f
        score += paymentCount  * 0.15f
        score += infoCount     * 0.20f
        score += secrecyCount  * 0.25f

        return score.coerceAtMost(1.0f)
    }

}
