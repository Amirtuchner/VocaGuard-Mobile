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
                // Hebrew — withdrawal block / fee extortion
                "דמי משיכה", "דמי שחרור", "עמלת עיבוד", "עמלת משיכה",
                "אגרת מס למשיכה", "ביטוח למשיכה", "שחרור כספים",
                // Hebrew — crypto / DeFi
                "ארנק קריפטו", "כתובת ארנק", "מטבעות דיגיטליים",
                "בלוקצ'יין", "טוקן", "לפני ההנפקה", "סטייקינג",
                "כריית מטבעות", "בריכת נזילות",
                // Hebrew — stock / general investment
                "בורסת ישראל", "מניות", "אופציות", "תיק השקעות",
                "השקעה ללא סיכון", "הפקדה מינימלית", "פתיחת חשבון מסחר",
                "תשואה חודשית", "חופש כלכלי", "מנהל חשבון אישי",
                "קבוצת סיגנלים", "קבוצת מסחר vip",
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
                "תרומה דחופה", "סיוע לנפגעי אסון", "עמותה רשומה",
                "תרומה לחיילים", "קרן לנפגעי טרור",
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
                "אל תתקשר לבנק", "חקירה חסויה", "לא לספר לבן זוג",
                "אנחנו מנסים להגן עליך", "תעבור את הכסף לחשבון בטוח",
                "מחלקת הונאות", "אל תתקשר לאף אחד",
                "אני הסוכן שלך", "מספר התג שלי", "מספר העובד שלי",
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
                "אשלם לך בחזרה", "חירום רפואי", "אני לא יכול לגשת לכסף שלי",
                "בעיית ויזה", "אני צריך את עזרתך", "רק הפעם הזאת",
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
                "משלוח ממתין", "ניסיון משלוח נכשל", "דואר ישראל",
                "אגרת מכס", "שלם לשחרור החבילה", "עדכון משלוח",
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
                "לא נדרש ניסיון", "עמלה קבועה", "עמלת עיבוד",
                "שכר גבוה מיידי", "תהיה הבוס של עצמך", "כסף קל מהבית",
                "משרה מיידית", "העבר תשלומים עבורנו",
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
            "בדחיפות", "חלון הזדמנויות", "לפני שיהיה מאוחר",
            "הזמן אוזל", "היום בלבד",
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
            "צו מעצר", "צו בית משפט", "כתב אישום", "הוצאה לפועל",
            "שבס", "צו עיכוב יציאה", "תביעה משפטית", "הליך פלילי",
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
            "אל תתקשר לבנק", "שמור בסוד", "לא לספר לבן זוג",
            "חקירה חסויה", "אל תתקשר לאף אחד", "אל תגלה לאיש",
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
            "טיקטוק", "יועץ פיננסי אישי", "ראיתי שהתעניינת",
            "נרשמת דרך הפרסומת", "אין סיכון לכסף",
            "תשואה עקבית", "מנהל חשבון אישי",
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
            "פפר פיי", "גוגל פיי", "אפל פיי", "פייפאל",
            "כרטיס מתנה", "מזומן", "כרטיס נטען", "העברה מיידית",
            "קנה כרטיסי מתנה",
            // Arabic
            "بطاقة هدية", "بيتكوين", "تحويل مالي", "ويسترن يونيون",
            // Spanish
            "tarjeta de regalo", "transferencia bancaria", "western union", "pague ahora",
            // French
            "carte cadeau", "virement bancaire", "western union", "payez maintenant"
        )

        // ── Victim-side detection ─────────────────────────────────────────────────
        // Patterns for what a VICTIM says during a scam call, not the scammer.
        // Used in mic-only mode (when call forwarding is unavailable) where only
        // the user's microphone is captured.  An alert requires ≥2 categories to
        // be present in the accumulated transcript, preventing false positives from
        // normal banking calls or everyday speech.

        /** Victim reads back sensitive data they were asked to provide. */
        private val VICTIM_DISCLOSING = listOf(
            // English
            "the code is", "verification code is", "otp is", "one time password is",
            "my pin is", "the pin is", "my password is", "the password is",
            "my account number is", "account ending in", "card number is",
            "my credit card number", "my social security", "date of birth is",
            "my id number is", "the cvv is", "security code on the back",
            "the six digit code", "the four digit code", "my routing number",
            "expiry date is", "expiration date is", "the three digits",
            // Hebrew
            "הקוד הוא", "קוד האימות הוא", "הסיסמה שלי היא",
            "מספר החשבון שלי", "מספר הכרטיס שלי", "תאריך הלידה שלי",
            "מספר תעודת הזהות שלי", "קוד חד פעמי", "הפין שלי", "הסיסמה היא",
            "הקוד שקיבלתי", "שלוש הספרות מאחורי הכרטיס",
            "תאריך התפוגה של הכרטיס", "המספר בגב הכרטיס",
            "מספר הניתוב שלי", "ארבע הספרות האחרונות"
        )

        /** Victim actively performs a dangerous financial or remote-access action. */
        private val VICTIM_COMPLYING = listOf(
            // English — ATM / cash
            "i'm at the atm", "going to the atm", "i went to the atm", "at the atm right now",
            "i withdrew the cash", "withdrawing the cash", "withdrew the money",
            "i'm at the bank withdrawing", "went to the bank to withdraw",
            // English — gift cards
            "i bought gift cards", "i have the gift cards", "i got the gift cards",
            "bought a gift card", "purchasing gift cards", "gift card numbers are",
            "numbers on the back of the card", "reading the numbers off the back",
            // English — money transfer
            "i transferred the money to them", "i sent the money to you",
            "i wired the money", "i completed the transfer", "i sent the wire",
            "the money has been sent", "transfer has been completed",
            // English — remote access
            "i downloaded anydesk", "i installed anydesk", "i downloaded teamviewer",
            "i installed teamviewer", "you can see my screen", "you have access to my computer",
            "i allowed the remote", "i clicked allow", "i installed the app you sent",
            "i granted access", "you have control of my",
            // English — crypto
            "i'm sending the bitcoin", "i bought bitcoin", "i purchased bitcoin",
            "sending to the wallet address", "the bitcoin has been sent",
            // Hebrew — ATM / cash
            "אני בכספומט", "הלכתי לכספומט", "משכתי מזומן", "אני מושך מזומן",
            "הלכתי לבנק למשוך",
            // Hebrew — gift cards
            "קניתי כרטיסי מתנה", "קניתי גיפט קארד", "יש לי את הכרטיסים",
            "מספרי הגיפט קארד", "הספרות מאחורי הכרטיס",
            // Hebrew — transfer
            "העברתי את הכסף אליהם", "שלחתי את הכסף אליך", "ביצעתי העברה בנקאית",
            "ההעברה הושלמה",
            // Hebrew — remote access
            "הורדתי אניידסק", "הורדתי טימויוור", "התקנתי את האפליקציה שלחת",
            "אתה רואה את המסך שלי", "יש לך גישה למחשב שלי",
            // Hebrew — crypto
            "קניתי ביטקוין", "שולח ביטקוין", "הביטקוין נשלח",
            // English — investment action (victim following scammer instructions)
            "i already transferred the money", "i sent the money already",
            "did you receive the payment", "have you received my payment",
            "which wallet address should i use", "what wallet address do i send to",
            "do i need to convert the money to cryptocurrency", "do i need to buy crypto",
            "can i pay by bank transfer", "can i do a bank transfer",
            "where should i send the money", "where do i send the money",
            "what is anydesk", "what is team viewer",
            "i've opened the crypto wallet", "i opened the crypto wallet",
            "i set up the wallet", "i created the wallet",
            "i'm installing anydesk", "i installed the anydesk",
            // Hebrew — investment actions
            "כבר העברתי את הכסף", "שלחתי את הכסף כבר",
            "קיבלת את התשלום", "האם קיבלת את הכסף",
            "איזה כתובת ארנק אני שולח", "לאיזה כתובת ארנק",
            "אני צריך להמיר את הכסף לקריפטו", "אני צריך לקנות קריפטו",
            "אפשר לשלם בהעברה בנקאית", "אפשר להעביר בנקאית",
            "לאן אני שולח את הכסף", "לאן אני צריך לשלוח",
            "מה זה אניידסק", "מה זה טימויוור",
            "פתחתי את ארנק הקריפטו", "פתחתי ארנק קריפטו",
            "יצרתי את הארנק", "התקנתי אניידסק"
        )

        /** Victim expresses fear in response to arrest / legal threats. */
        private val VICTIM_FEARING = listOf(
            // English
            "please don't arrest me", "i don't want to be arrested",
            "i don't want to go to jail", "i'm scared", "i am very scared",
            "i'm terrified", "i'm very worried about this",
            "i'll cooperate fully", "i'll do whatever you say",
            "i'll do anything you ask", "please don't take me to court",
            "i don't want any trouble", "i'll do whatever it takes",
            "i don't want to lose everything", "please help me resolve this",
            // Hebrew
            "אנא אל תעצור אותי", "אני לא רוצה ללכת לכלא",
            "אני פוחד מאוד", "אני מפחד מאוד", "אני מפחדת מאוד",
            "אעשה כל מה שתגיד", "אשתף פעולה באופן מלא",
            "אני לא רוצה בעיות", "אנא עזור לי לפתור את זה",
            "אני לא רוצה לאבד הכל",
            "אני מבהיל", "אני בלחץ", "אני מודאג מאוד",
            "אני אעשה הכל", "בבקשה אל תעצור אותי",
            "אני לא רוצה שייקחו לי את הבית", "אני לא רוצה משפט"
        )

        /** Victim agrees to hide the call from family / police (very strong signal). */
        private val VICTIM_CONCEALING = listOf(
            // English
            "i won't tell anyone", "i won't tell my family",
            "i'll keep it between us", "i'll keep it a secret",
            "i won't mention this to anyone", "okay i won't say anything",
            "i understand i shouldn't tell", "i won't say a word",
            "i promise i won't tell", "i'll stay quiet about this",
            "i won't call the police", "i won't contact my bank about this",
            // Hebrew
            "לא אספר לאף אחד", "לא אספר למשפחה שלי",
            "אשמור את זה בסוד", "לא אגיד לאף אחד",
            "אני מבין שלא אמור לספר", "לא אגלה לאיש", "אשתוק על זה",
            "לא אתקשר למשטרה", "לא אפנה לבנק בקשר לזה",
            "אני מבטיח לא לספר", "לא אגיד מילה"
        )

        /**
         * Victim phrases that are virtually impossible in a legitimate call.
         * A single match triggers a high-confidence alert without needing a second category.
         */
        private val VICTIM_CRITICAL_PHRASES = setOf(
            // Authority / arrest threats
            "please don't arrest me",
            "i don't want to go to jail",
            "i don't want to be arrested",
            // Gift card / cash extraction
            "reading the numbers off the back",
            "the gift card numbers are",
            // Crypto sending
            "i'm sending you the bitcoin",
            // Investment scam: sharing an OTP with a "financial advisor" is never legitimate
            "i received a verification code",
            "i got a verification code",
            "the verification code i received",
            // Investment scam: bank flagged the transfer in real-time — very specific signal
            "the bank is asking why i'm transferring",
            "the bank wants to know why i'm sending",
            "the bank is blocking my transfer",
            "the teller is asking why",
            // Investment scam: crypto wallet opened + asking for wallet address
            "i've opened the crypto wallet which wallet address should i use",
            "i opened the crypto wallet which wallet address should i use",
            // Hebrew
            "אנא אל תעצור אותי",
            "אני לא רוצה ללכת לכלא",
            "קיבלתי קוד אימות",
            "הבנק שואל למה אני מעביר את הכסף",
            "הבנק חוסם לי את ההעברה",
            "פתחתי את ארנק הקריפטו לאיזה כתובת לשלוח"
        )

        /**
         * Questions and statements a victim makes while being coached through an
         * investment scam.  These phrases CAN appear in legitimate financial
         * conversations, so this category alone never triggers an alert — it must
         * combine with at least one other victim category (COMPLYING, DISCLOSING,
         * FEARING, or CONCEALING).
         */
        private val VICTIM_INVESTMENT_COACHING = listOf(
            // English — due-diligence questions victims ask while being pitched
            "how will i know that i won't lose money",
            "how do i know i won't lose my money",
            "what do i need to do to get started",
            "what do i need to do next",
            "when will i be able to withdraw my money",
            "when can i withdraw my money",
            "when can i take out my money",
            "when will the financial consultant call me",
            "when will my advisor call me",
            "when will my account manager call me",
            "how much do i need to invest",
            "what is the minimum investment",
            "how much profit will i make",
            "how much can i earn",
            "what return will i get",
            // English — withdrawal block / fee extortion (victim pushes back)
            "why do i need to send more money",
            "why do i have to pay more",
            "i thought i already paid everything",
            "i don't have that amount available",
            "i don't have that much money",
            "i can't afford to send that much",
            "why has my account manager stopped answering",
            "why isn't my account manager responding",
            "why can't i access my account",
            "why can't i withdraw",
            "i've been trying to withdraw for weeks",
            // English — legitimacy / skepticism signals
            "how do i know this is legitimate",
            "how do i know you're real",
            "can you prove this is a real company",
            "why are you calling from a different number",
            "why is the number different",
            "can i call you back using the official number",
            "can i verify this with the company directly",
            "can you send me the documents",
            "can you send me something in writing",
            "can i think about it first",
            "i need more time to think",
            "i'm not comfortable making this decision now",
            "i need to speak to someone i trust first",
            "i want to ask my family first",
            // English — payment / logistics
            "can i pay in installments",
            "can i split the payment",
            "can we continue tomorrow",
            "can we talk again tomorrow",
            // English — disengagement (victim trying to stop)
            "stop contacting me",
            "please stop calling me",
            "i want to stop",
            "i don't want to continue",
            "i've changed my mind",
            // Hebrew — due-diligence / pitch
            "מאיפה אני יודע שלא אפסיד כסף",
            "איך אני יודע שלא אפסיד",
            "מה אני צריך לעשות",
            "מתי אוכל למשוך את הכסף שלי",
            "מתי אפשר למשוך את הכסף",
            "מתי היועץ הפיננסי יתקשר אלי",
            "מתי מנהל החשבון שלי יתקשר",
            "כמה אני צריך להשקיע",
            "כמה רווח אני ארוויח",
            "מה התשואה שאקבל",
            // Hebrew — withdrawal block
            "למה אני צריך לשלוח עוד כסף",
            "למה אני צריך לשלם עוד",
            "אין לי את הסכום הזה",
            "למה מנהל החשבון שלי הפסיק לענות",
            "למה אני לא יכול למשוך את הכסף",
            "ניסיתי למשוך כבר שבועות",
            // Hebrew — skepticism
            "איך אני יודע שזה לגיטימי",
            "איך אני יודע שאתם חברה אמיתית",
            "למה אתה מתקשר ממספר אחר",
            "אפשר להתקשר אליך דרך המספר הרשמי",
            "אתה יכול לשלוח לי את המסמכים",
            "אפשר לחשוב על זה קודם",
            "אני לא נוח לקבל החלטה עכשיו",
            "אני רוצה לשאול את המשפחה שלי",
            // Hebrew — payment / disengagement
            "אפשר לשלם בתשלומים",
            "אפשר להמשיך מחר",
            "הפסק להתקשר אלי",
            "אני לא רוצה להמשיך",
            "שיניתי את דעתי"
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
            "מספר כרטיס אשראי", "תאריך תפוגה", "שלוש ספרות בגב",
            "קוד אימות", "קוד חד פעמי", "תאריך לידה", "מספר תעודת זהות",
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
                "משיכת רווחים", "הפקדה מינימלית", "מנהל חשבון אישי",
                "רווחים מובטחים", "השקעה ללא סיכון", "ההון שלך מוגן",
                "יועץ השקעות", "פתיחת חשבון מסחר", "עמלת משיכה",
                "ביטוח למשיכה", "שחרור כספים", "אגרת מס למשיכה",
                "ארנק קריפטו", "מטבעות דיגיטליים", "סטייקינג",
                "כריית מטבעות", "בריכת נזילות", "בורסת ישראל",
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
                "לחץ על הקישור", "חשבונך יושעה", "פעילות חריגה",
                "עדכן סיסמה", "הזן את הקוד", "פרטיך עודכנו",
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
                "הכרטיס שלך הושעה", "פריצה לחשבון", "מחלקת הונאות",
                "החשבון שלך נחסם", "עסקה חשודה", "בנק הפועלים",
                "בנק לאומי", "ישראכרט", "כאל",
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
                "אל תתקשר לבנק", "אל תתקשר למשטרה", "חקירה חסויה",
                "לא לספר לבן זוג", "הזהות שלך נגנבה", "העבר לחשבון בטוח",
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

    /**
     * Detects scam patterns from the VICTIM's side of the call.
     *
     * Called when only microphone audio is available (no call forwarding).
     * Rather than looking for scammer phrases, this
     * method analyses what the VICTIM says — sensitive data disclosures,
     * compliance with dangerous instructions, fear responses to threats, and
     * agreements to conceal the call.
     *
     * An alert requires ≥2 distinct categories in [fullTranscript], or a single
     * match from [VICTIM_CRITICAL_PHRASES] which are virtually impossible in a
     * legitimate call.  Single-category matches (e.g. "my account number is")
     * are intentionally below threshold to avoid false positives on normal
     * banking calls.
     *
     * @param chunk         The latest Vosk recognition result (a few words).
     * @param fullTranscript The entire accumulated transcript for the call so far.
     */
    fun analyzeVictimSpeech(chunk: String, fullTranscript: String): DetectionResult {
        val lowerChunk      = chunk.lowercase()
        val lowerTranscript = fullTranscript.lowercase()

        // 1. Critical phrase in the latest chunk — one match is enough
        val criticalMatch = VICTIM_CRITICAL_PHRASES.firstOrNull { containsWord(lowerChunk, it) }
        if (criticalMatch != null) {
            Log.w(TAG, "Victim critical phrase: \"$criticalMatch\"")
            // Investment-related critical phrases → INVESTMENT_SCAM; authority threats → SOCIAL_ENGINEERING
            val criticalScamType = when {
                "verification code" in criticalMatch || "wallet" in criticalMatch ||
                "crypto" in criticalMatch || "bank is asking" in criticalMatch ||
                "bank wants to know" in criticalMatch || "bank is blocking" in criticalMatch ||
                "teller is asking" in criticalMatch -> ScamType.INVESTMENT_SCAM
                else -> ScamType.SOCIAL_ENGINEERING
            }
            return DetectionResult(
                isScam     = true,
                scamType   = criticalScamType,
                confidence = 0.88f,
                reason     = "Victim critical phrase detected: \"$criticalMatch\"",
                keywords   = listOf(criticalMatch)
            )
        }

        // 2. Multi-category analysis on the full accumulated transcript
        val disclosing  = VICTIM_DISCLOSING.filter           { containsWord(lowerTranscript, it) }
        val complying   = VICTIM_COMPLYING.filter            { containsWord(lowerTranscript, it) }
        val fearing     = VICTIM_FEARING.filter              { containsWord(lowerTranscript, it) }
        val concealing  = VICTIM_CONCEALING.filter           { containsWord(lowerTranscript, it) }
        // Investment coaching: questions/statements while being pitched a fraudulent investment.
        // Safe alone — requires combination with any other category to fire.
        val coaching    = VICTIM_INVESTMENT_COACHING.filter  { containsWord(lowerTranscript, it) }

        val categoriesPresent = listOf(disclosing, complying, fearing, concealing, coaching)
            .count { it.isNotEmpty() }

        // Require ≥2 categories — a single category is intentionally below threshold.
        // e.g. "how much do I need to invest?" alone is a normal financial question;
        //      combined with "I already transferred the money" it is a strong scam signal.
        if (categoriesPresent < 2) {
            return DetectionResult(
                isScam     = false,
                scamType   = ScamType.UNKNOWN,
                confidence = 0f,
                reason     = "Insufficient victim-side signals ($categoriesPresent category)"
            )
        }

        val allMatches = disclosing + complying + fearing + concealing + coaching
        val baseConfidence = when (categoriesPresent) {
            2    -> 0.72f
            3    -> 0.82f
            4    -> 0.89f
            else -> 0.93f
        }
        // Small bonus per extra match (capped) — more signals = more certain
        val confidence = (baseConfidence + (allMatches.size * 0.02f).coerceAtMost(0.07f))
            .coerceAtMost(0.95f)

        // Investment coaching + any action/disclosure = investment scam;
        // fear/secrecy signals = social engineering (authority impersonation);
        // everything else defaults to social engineering.
        val scamType = when {
            coaching.isNotEmpty() && (complying.isNotEmpty() || disclosing.isNotEmpty()) ->
                ScamType.INVESTMENT_SCAM
            coaching.isNotEmpty() -> ScamType.INVESTMENT_SCAM
            fearing.isNotEmpty() || concealing.isNotEmpty() -> ScamType.SOCIAL_ENGINEERING
            complying.any { "bitcoin" in it || "crypto" in it || "wallet" in it } ->
                ScamType.INVESTMENT_SCAM
            complying.any { "gift card" in it || "wire" in it } -> ScamType.SOCIAL_ENGINEERING
            disclosing.isNotEmpty() && complying.isNotEmpty() -> ScamType.BANK_FRAUD
            else -> ScamType.SOCIAL_ENGINEERING
        }

        Log.w(TAG, "Victim-side scam: $categoriesPresent categories, ${allMatches.size} signals, " +
            "type=$scamType, confidence=$confidence")
        return DetectionResult(
            isScam     = true,
            scamType   = scamType,
            confidence = confidence,
            reason     = "Victim-side signals — D:${disclosing.size} C:${complying.size} " +
                "F:${fearing.size} S:${concealing.size} I:${coaching.size}; " +
                "samples: ${allMatches.take(3).joinToString(", ")}",
            keywords   = allMatches.take(5)
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
