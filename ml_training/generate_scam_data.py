#!/usr/bin/env python3
"""
Generate synthetic training data for underrepresented scam classes.
Targets:
  Label  9 — Investment Scam  (add ~630 rows → total ~990)
  Label 11 — Romance/Pig-Butchering (add ~910 rows → total ~980)
  Label 12 — Delivery/Package (add ~915 rows → total ~980)
  Label 13 — Job/Recruitment  (add ~920 rows → total ~980)

Run from ml_training/:
  python generate_scam_data.py
"""

import csv, random, itertools, pathlib, sys

random.seed(42)

# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------
CSV_HEADER = ["text","label","avg_rms","rms_std_dev","silence_ratio",
              "had_long_silence","call_duration_sec","call_hour",
              "speaker_switches","noise_floor_db","speech_rate_wpm","dtmf_detected"]
ZERO_AUDIO = ["0"]*9   # placeholders for audio columns

def row(text, label):
    return [text, str(label)] + ZERO_AUDIO

def pick(*pools):
    """Pick one item at random from each pool and concatenate."""
    return " ".join(random.choice(p) for p in pools)

def shuffle_join(sentences):
    random.shuffle(sentences)
    return " ".join(sentences)

# ---------------------------------------------------------------------------
# LABEL 9 — Investment Scam
# Covers: crypto, forex, binary options, pig butchering, Ponzi/pyramid,
#         stock pump-and-dump, fake hedge fund, ICO/token, commodity trading
# ---------------------------------------------------------------------------
INVEST_OPENERS = [
    "Hello, I'm reaching out because we've identified you as a sophisticated investor.",
    "Good afternoon, I'm calling from our exclusive wealth management division.",
    "Hi, our algorithm selected your profile for a special investment opportunity.",
    "Good morning, I'm your dedicated account manager at our trading platform.",
    "Hello, congratulations — you've been pre-approved for our VIP trading program.",
    "Hi there, I'm contacting you about an exclusive crypto investment opportunity.",
    "Good day, our financial analysts have identified a rare market opportunity for you.",
    "Hello, I represent a licensed offshore investment firm with outstanding returns.",
    "Hi, we noticed you expressed interest in passive income — we can help you achieve that.",
    "Good evening, I'm your personal broker assigned to help grow your portfolio.",
    # Russian
    "Здравствуйте, я звоню по поводу эксклюзивной инвестиционной возможности.",
    "Добрый день, наша торговая платформа выбрала вас для VIP программы.",
    "Привет, я ваш личный брокер и хочу предложить вам уникальный шанс заработать.",
    # Hebrew
    "שלום, אני מתקשר בנוגע להזדמנות השקעה בלעדית שמתאימה לפרופיל שלך.",
    "היי, אני מנהל החשבון האישי שלך ואני רוצה לעדכן אותך על פלטפורמת המסחר שלנו.",
    # Arabic
    "مرحبًا، أتصل بك بخصوص فرصة استثمارية حصرية اخترناك لها.",
    "صباح الخير، أنا مستشارك المالي الشخصي ولدي عرض استثماري رائع لك.",
]

INVEST_PITCH = [
    "Our platform has generated average monthly returns of 15 to 30 percent for our clients.",
    "We specialise in crypto arbitrage — buying low on one exchange and selling high on another, completely risk-free.",
    "Our AI-driven forex robot trades automatically and has never had a losing month.",
    "We offer a guaranteed 20 percent return on your initial deposit within 30 days.",
    "Our hedge fund uses a proprietary algorithm that beats the market every single quarter.",
    "This is a binary options strategy — you simply predict whether Bitcoin goes up or down and earn 85 percent profit.",
    "We are launching an ICO next week and early investors receive a 10x return at token listing.",
    "Our commodity trading desk focuses on gold and oil — both are skyrocketing right now.",
    "Your initial investment of just five thousand dollars will be worth fifty thousand in six months.",
    "We have VIP accounts with access to insider market signals not available to the public.",
    "Our clients made over two hundred percent profit last year — that's documented.",
    "This is a limited time offer — only five spots remaining at this guaranteed rate.",
    "We manage everything for you. You deposit, we trade, you collect passive income every week.",
    "Your money is fully insured by our international regulatory body — zero risk.",
    "Our minimum deposit is one thousand dollars and you can withdraw profits anytime.",
    "The platform uses blockchain technology to ensure complete transparency and security.",
    "We offer a referral bonus — bring a friend and earn an extra ten percent commission.",
    # Russian
    "Наша платформа обеспечивает гарантированную доходность от 15 до 30 процентов в месяц.",
    "Мы торгуем криптовалютой с использованием ИИ — никаких убытков за последние два года.",
    "Вложите всего тысячу долларов и получайте пассивный доход каждую неделю.",
    "Наш алгоритм форекс-трейдинга работает круглосуточно и приносит стабильную прибыль.",
    # Hebrew
    "הפלטפורמה שלנו מספקת תשואה מובטחת של 20 אחוז בחודש על ההשקעה שלך.",
    "אנחנו מתמחים במסחר בקריפטו עם בינה מלאכותית — שום חודש הפסד בשנתיים האחרונות.",
    "השקעה של אלף דולר בלבד ותקבל הכנסה פסיבית כל שבוע ישירות לארנק שלך.",
    # Arabic
    "منصتنا توفر عوائد مضمونة تتراوح بين 15 و30 بالمئة شهريًا.",
    "نحن متخصصون في تداول العملات المشفرة بالذكاء الاصطناعي — لم نخسر شهرًا واحدًا.",
]

INVEST_URGENCY = [
    "This offer closes at midnight tonight — I need your decision now.",
    "We only have three spots left at this guaranteed rate so you must decide today.",
    "The market window opens in 48 hours — you need to fund your account immediately.",
    "Our compliance team requires the deposit by end of business today to lock in your rate.",
    "I've been fighting for your spot in this VIP program — please don't let me down.",
    "Other clients are already funding — you'll lose this opportunity if you wait.",
    "The token presale ends in 24 hours — after that the price triples.",
    "Your account will be deactivated if you don't make the minimum deposit today.",
    "I can only hold this offer for another two hours — my manager is pressuring me.",
    "Transfer the funds via wire transfer or cryptocurrency — we accept Bitcoin and USDT.",
    "You can start with as little as five hundred dollars — wire it to our escrow account today.",
    "Send the funds to our company wallet address and you'll see the balance reflected within minutes.",
    # Russian
    "Предложение действует только до конца сегодняшнего дня — нужно решение прямо сейчас.",
    "Осталось только три места в VIP программе — не упустите шанс.",
    # Hebrew
    "ההצעה תקפה רק עד חצות הלילה — אני צריך ממך החלטה עכשיו.",
    "נשארו רק שלושה מקומות בתוכנית ה-VIP — אל תפספס את ההזדמנות הזו.",
    # Arabic
    "هذا العرض ينتهي في منتصف الليل — أحتاج قرارك الآن.",
    "تبقى فقط ثلاثة أماكن في البرنامج المميز — لا تفوت هذه الفرصة.",
]

INVEST_WITHDRAWAL_BLOCK = [
    "To unlock your withdrawal you must first pay a 15 percent tax clearance fee.",
    "Our compliance department requires a refundable security deposit before releasing your profits.",
    "Your account has been flagged for verification — pay the upgrade fee to restore withdrawal access.",
    "The financial regulator requires a one-time fee to process international transfers of this size.",
    "Your profits are ready but you need to cover the broker commission first — only two thousand dollars.",
    "Unfortunately a technical issue froze your account — pay the maintenance fee to unlock it.",
    "To withdraw your 50,000 dollar profit you need to pay a 5,000 dollar insurance bond first.",
    "Your account tier needs to be upgraded — a one-time payment of 3,000 dollars unlocks full withdrawal.",
    "The anti-money laundering department requires a deposit to verify your identity before releasing funds.",
    "Your trading account shows a profit of 85,000 dollars — just pay the processing fee to withdraw.",
]

INVEST_CRYPTO = [
    "We operate a Bitcoin arbitrage fund — your coins never leave your wallet, we just use the trading rights.",
    "Our DeFi staking pool offers 200 percent APY — just connect your MetaMask and approve the contract.",
    "This altcoin is about to be listed on Binance — buy now before the price explodes.",
    "Send your Ethereum to our smart contract address and receive double tokens in 24 hours.",
    "Our crypto signal group has an 89 percent win rate — join the VIP Telegram channel now.",
    "This new token is backed by a real gold reserve — it can only go up.",
    "We have insider information about a major exchange listing next week — buy the token today.",
    "Your Bitcoin investment has grown to 45,000 dollars — just pay the withdrawal tax to receive it.",
    "Our mining pool distributes daily earnings directly to your wallet — zero effort passive income.",
    "Invest in our NFT project — floor price is guaranteed to increase tenfold at launch.",
    # Russian
    "Наш крипто-фонд предлагает 200 процентов годовых через DeFi стейкинг.",
    "Этот альткоин будет листинг на Binance — покупайте сейчас пока цена низкая.",
    # Hebrew
    "הקרן שלנו לביטקוין ארביטראז' מציעה 200 אחוז APY — פשוט חבר את הארנק שלך.",
    "הטוקן הזה עומד להירשם ב-Binance — קנה עכשיו לפני שהמחיר יקפוץ.",
]

INVEST_PONZI = [
    "You earn 10 percent on every person you bring into the program — build your downline and earn forever.",
    "Our network marketing investment opportunity pays weekly commissions on three levels.",
    "Recruit five members and your initial investment is fully covered within a week.",
    "This is a peer-to-peer lending platform — lend your money to other members and earn 20 percent monthly.",
    "Our investment club pools money from members and distributes profits proportionally every Friday.",
    "This is not a Ponzi scheme — it is a legitimate profit-sharing cooperative with thousands of members.",
    "Join our global investment community — members earn passive income while sleeping.",
    "Your upline mentor earned 100,000 dollars last year — let us show you how to do the same.",
]

def make_investment_rows(n):
    rows = []
    pools = [
        lambda: pick(INVEST_OPENERS) + " " + pick(INVEST_PITCH),
        lambda: pick(INVEST_OPENERS) + " " + pick(INVEST_PITCH) + " " + pick(INVEST_URGENCY),
        lambda: pick(INVEST_PITCH) + " " + pick(INVEST_URGENCY),
        lambda: pick(INVEST_OPENERS) + " " + pick(INVEST_CRYPTO),
        lambda: pick(INVEST_CRYPTO) + " " + pick(INVEST_URGENCY),
        lambda: pick(INVEST_WITHDRAWAL_BLOCK),
        lambda: pick(INVEST_PONZI) + " " + pick(INVEST_URGENCY),
        lambda: pick(INVEST_OPENERS) + " " + pick(INVEST_PONZI),
        lambda: pick(INVEST_PITCH) + " " + pick(INVEST_WITHDRAWAL_BLOCK),
        lambda: pick(INVEST_CRYPTO) + " " + pick(INVEST_WITHDRAWAL_BLOCK),
    ]
    for i in range(n):
        fn = pools[i % len(pools)]
        rows.append(row(fn(), 9))
    return rows

# ---------------------------------------------------------------------------
# LABEL 11 — Romance / Pig-Butchering / Grandparent Scam
# ---------------------------------------------------------------------------
ROMANCE_INTRO = [
    # Every entry contains at least one feature-43 keyword
    "My darling, I feel like we have a deep connection even though we've only met online.",
    "Hey baby, I'm so glad I found you on the dating app — you are my soulmate.",
    "Hi, I'm a US army officer currently deployed overseas — I fell in love with your profile.",
    "Hello dear, I met you online and I've fallen deeply in love with you.",
    "My dearest, our long-distance relationship means the world to me — I want to be with you forever.",
    "Hello, I'm a successful businessman and we met online — I trust you completely.",
    "Grandma it's me, please don't hang up — I'm in serious trouble and I need your help right now.",
    "Grandpa, it's your grandson. I've been in an accident and I'm at the police station.",
    "Hey Grandma, please don't tell Mom — I messed up and I need money urgently.",
    "Nana it's me, I got into a car accident and I need bail money — please help me.",
    "I met you online and my feelings are very real — this is a long-distance relationship I treasure.",
    "My darling, I am stuck abroad and I need your help — don't tell mom about this.",
    # Russian
    "Привет дорогой, я так рада что мы познакомились онлайн — ты моя душа.",
    "Бабушка, это я — я попал в беду и мне срочно нужна твоя помощь, пожалуйста не говори маме.",
    # Hebrew
    "יקירי, אני שמחה שנפגשנו אונליין — את הנשמה שלי ואני אוהב אותך.",
    "סבתא, זה אני — אני בצרות גדולות ואני צריך את עזרתך עכשיו, בבקשה אל תגידי לאמא.",
    # Arabic
    "حبيبي، أنا سعيد جدًا بلقائك عبر الإنترنت — أنت روحي الجميلة.",
    "جدتي، أنا في ورطة كبيرة وأحتاج مساعدتك الآن — من فضلك لا تخبري أمي.",
]

ROMANCE_INVESTMENT = [
    "I wanted to share an investment opportunity that has been life-changing for me — a crypto trading platform.",
    "My cousin works at a trading firm and let me in on a secret — we can invest together and double our money.",
    "I've been making incredible returns on this platform — I want you to join so we can build a future together.",
    "Let me add you to our private investment group — you just deposit a small amount and watch it grow.",
    "I've already invested fifty thousand and my balance is now two hundred thousand — do this with me.",
    "This is a pig butchering opportunity — I can guide you through every step of the investment.",
    "Just download this trading app, create an account, and send your first deposit — I'll guide you.",
    "Our love is real and I want to secure our future together — invest with me on this platform.",
]

ROMANCE_MONEY_REQUEST = [
    "I've been in an accident abroad and my wallet was stolen — can you send me five hundred dollars via wire?",
    "I'm stuck at customs and they are holding my shipment — I need three thousand dollars in fees.",
    "My bank account was frozen overseas — please send money via Western Union so I can fly home to you.",
    "I'm in the hospital and insurance won't cover it — can you help with two thousand dollars?",
    "My business partner stole from me — I'm desperate, can you send Bitcoin to this address?",
    "The embassy requires a bond fee to release my passport — please help me with a thousand dollars.",
    "I need money to buy my plane ticket home — once I arrive we can be together forever.",
    "Please send gift cards — Amazon or iTunes — and scratch off the codes and send me the numbers.",
    "I'm in jail and need bail money — please wire five thousand dollars immediately, don't tell anyone.",
    "Grandma please send money to my lawyer — his name is Mr. Johnson and he needs two thousand cash.",
    "Grandpa, I don't want you to worry but I need bail money sent by wire transfer today.",
    "Please don't tell my parents — send the money through Western Union to this name and address.",
    # Russian
    "Застрял за границей, кошелёк украли — пожалуйста переведи мне пятьсот долларов.",
    "Бабушка пожалуйста пошли деньги на залог — не говори маме, это срочно.",
    # Hebrew
    "נתקעתי בחו\"ל והארנק נגנב — אפשר שתשלח לי חמש מאות דולר בהעברה בנקאית?",
    "סבתא בבקשה שלח כסף לעורך הדין שלי — הוא צריך אלפיים שקל במזומן עכשיו.",
    # Arabic
    "عالقت في الخارج وسرقوا محفظتي — هل يمكنك إرسال خمسمئة دولار عبر الحوالة؟",
    "جدتي من فضلك أرسلي الكفالة المالية — لا تخبري والدي، هذا عاجل.",
]

ROMANCE_TRUST_BUILD = [
    # Anchor with feature-43 keyword so every trust-build row is identifiable
    "I trust you completely — we met online and I have never felt this way about anyone in a long-distance relationship.",
    "My darling, I would never ask this if I wasn't in a genuine emergency — our relationship means everything.",
    "Once I come back from deployment we will finally meet — I promise to repay every cent I borrowed.",
    "I trust you completely and I hope you trust me — that's what a real relationship is built on.",
    "We will get married and I will take care of you forever — just help me through this one crisis.",
    "My feelings for you are very strong — I fell in love the moment we met online.",
    "Grandma, I am not the type of person who asks for money but this situation is exceptional.",
    "My soulmate, I showed my mother your photo and she loves you already — she can't wait to meet you.",
]

def make_romance_rows(n):
    rows = []
    pools = [
        lambda: pick(ROMANCE_INTRO) + " " + pick(ROMANCE_TRUST_BUILD),
        lambda: pick(ROMANCE_INTRO) + " " + pick(ROMANCE_MONEY_REQUEST),
        lambda: pick(ROMANCE_TRUST_BUILD) + " " + pick(ROMANCE_MONEY_REQUEST),
        lambda: pick(ROMANCE_INTRO) + " " + pick(ROMANCE_INVESTMENT),
        lambda: pick(ROMANCE_INVESTMENT) + " " + pick(ROMANCE_MONEY_REQUEST),
        lambda: pick(ROMANCE_INTRO) + " " + pick(ROMANCE_INVESTMENT) + " " + pick(ROMANCE_MONEY_REQUEST),
        lambda: pick(ROMANCE_INTRO) + " " + pick(ROMANCE_TRUST_BUILD) + " " + pick(ROMANCE_MONEY_REQUEST),
        lambda: pick(ROMANCE_TRUST_BUILD) + " " + pick(ROMANCE_INVESTMENT),
    ]
    for i in range(n):
        fn = pools[i % len(pools)]
        rows.append(row(fn(), 11))
    return rows

# ---------------------------------------------------------------------------
# LABEL 12 — Delivery / Package Scam
# ---------------------------------------------------------------------------
DELIVERY_NOTICE = [
    "Hello, this is an automated message from the postal service regarding your package.",
    "Hi, we attempted to deliver your parcel today but were unable to complete the delivery.",
    "Good afternoon, your package is being held at our facility pending a small customs fee.",
    "This is an important notice — your shipment has been flagged by customs for inspection.",
    "Hello, your Amazon order could not be delivered and has been returned to our warehouse.",
    "We are calling from the courier company regarding your parcel delivery.",
    "Your package worth three hundred dollars is ready for delivery — we just need to confirm your address.",
    "This is a delivery notification — your international shipment requires a clearance payment.",
    "Hi, you have a package that requires signature — please call back to arrange redelivery.",
    "Your DHL shipment is on hold pending verification of your delivery address and payment of fees.",
    "We have a parcel for you from overseas but it has been stopped at customs.",
    "Hello, this is FedEx calling about your package — there is an outstanding delivery fee.",
    # Russian
    "Здравствуйте, ваша посылка задержана на таможне — требуется уплата сбора.",
    "Добрый день, мы пытались доставить ваш пакет, но не смогли — нужно перезаписаться.",
    # Hebrew
    "שלום, החבילה שלך מוחזקת במכס ונדרש תשלום של דמי שחרור.",
    "היי, ניסינו למסור את החבילה שלך אבל לא הצלחנו — אנא התקשר לקביעת מועד חדש.",
    # Arabic
    "مرحبًا، طردك محتجز في الجمارك ويتطلب سداد رسوم تخليص جمركي.",
    "مساء الخير، حاولنا تسليم طردك لكن لم نتمكن — يرجى الاتصال لترتيب موعد جديد.",
]

DELIVERY_FEE = [
    "To release your package you need to pay a small customs clearance fee of just fifteen dollars.",
    "There is a redelivery fee of eight dollars fifty — please provide your card details to complete payment.",
    "Your parcel is being held due to an unpaid import duty of twenty two dollars.",
    "To avoid your package being returned to sender please pay the clearance fee immediately.",
    "We require a security deposit of fifty dollars to release the shipment — fully refundable upon delivery.",
    "Your package will be destroyed in 24 hours unless you pay the storage fee of thirty dollars.",
    "The customs authority requires payment before we can release your shipment to the delivery agent.",
    "To confirm redelivery please verify your credit card number and billing address over the phone.",
    "Pay the small fee via our online portal — we'll need your card number, expiry, and security code.",
    "Please transfer the customs fee via bank transfer or provide your card details now.",
    # Russian
    "Для получения посылки оплатите таможенный сбор в размере 15 долларов прямо сейчас.",
    "Нужна оплата сбора за хранение — введите данные вашей карты для завершения.",
    # Hebrew
    "כדי לקבל את החבילה יש לשלם דמי מכס של 15 דולר — אנא ספק את פרטי הכרטיס שלך.",
    "יש לשלם דמי אחסון כדי למנוע החזרת החבילה לשולח.",
    # Arabic
    "لاستلام طردك يرجى دفع رسوم الجمارك البالغة 15 دولارًا الآن.",
    "يرجى تقديم تفاصيل بطاقتك الائتمانية لإتمام عملية دفع رسوم التسليم.",
]

DELIVERY_PHISHING = [
    "Please click the link we sent to your phone to reschedule and pay the fee.",
    "We will send you a text message with a secure payment link — please complete it within one hour.",
    "To verify your identity please provide your full name, address, and credit card information.",
    "We need to update your delivery address — please confirm your details including your card number.",
    "Your tracking number is AB12345678 — visit our portal and enter your payment details to proceed.",
    "We are sending a verification code to your phone — please share it with our agent to confirm.",
    "Click the link to track your package and schedule redelivery — payment is required on the portal.",
]

def make_delivery_rows(n):
    rows = []
    pools = [
        lambda: pick(DELIVERY_NOTICE) + " " + pick(DELIVERY_FEE),
        lambda: pick(DELIVERY_NOTICE) + " " + pick(DELIVERY_PHISHING),
        lambda: pick(DELIVERY_FEE) + " " + pick(DELIVERY_PHISHING),
        lambda: pick(DELIVERY_NOTICE) + " " + pick(DELIVERY_FEE) + " " + pick(DELIVERY_PHISHING),
        lambda: pick(DELIVERY_NOTICE),
        lambda: pick(DELIVERY_FEE),
        lambda: pick(DELIVERY_NOTICE) + " " + pick(DELIVERY_FEE),
    ]
    for i in range(n):
        fn = pools[i % len(pools)]
        rows.append(row(fn(), 12))
    return rows

# ---------------------------------------------------------------------------
# LABEL 13 — Job / Recruitment Scam
# ---------------------------------------------------------------------------
JOB_OPENER = [
    # Every entry contains at least one feature-45 keyword
    "Hi, congratulations — your profile was selected for a high-paying work-from-home position.",
    "Hello, we are offering flexible remote work with excellent pay — no experience required.",
    "Good morning, our company is hiring data entry specialists — we found your resume online.",
    "Hi there, we found your CV online and we'd like to offer you a part-time job earning five thousand a month.",
    "Hello, I am calling from our talent acquisition team regarding a remote work opportunity.",
    "We are looking for mystery shoppers in your area — this is a paid part-time job offer.",
    "Congratulations, you have been selected for our work-from-home program — no experience needed.",
    "Hello, we are hiring package reshipment coordinators for remote work — start immediately.",
    "We found your resume on a job portal and want to offer you a work-from-home recruitment opportunity.",
    "Good afternoon, this is about a job offer — earn from home with flexible hours and no experience required.",
    # Russian
    "Здравствуйте, мы нашли ваше резюме и предлагаем работу из дома без опыта.",
    "Добрый день, наша компания нанимает удалённых сотрудников — работа из дома с хорошей зарплатой.",
    # Hebrew
    "שלום, ראינו את קורות החיים שלך ואנחנו מציעים לך הצעת עבודה מהבית ללא ניסיון.",
    "היי, אנחנו מגייסים עובדים לעבודה מהבית עם שכר גבוה — לא נדרש ניסיון.",
    # Arabic
    "مرحبًا، وجدنا سيرتك الذاتية ونود تقديم عرض عمل من المنزل بدون خبرة.",
    "صباح الخير، شركتنا تبحث عن موظفين للعمل من المنزل — وظيفة جزء الوقت براتب ممتاز.",
]

JOB_DETAILS = [
    "The role involves simple data entry tasks — you can work from home just two hours a day.",
    "As a mystery shopper you will purchase products, evaluate the store, and keep the merchandise.",
    "Your job is to receive packages at your home address and forward them to our international clients.",
    "We need someone to help process online payments — you receive transfers and forward them for a fee.",
    "The position requires no qualifications — just a bank account and willingness to work hard.",
    "You will be a brand ambassador — post reviews online and earn fifty dollars per post.",
    "Our remote job requires you to download our proprietary software and follow simple instructions.",
    "The training is free and you will earn your first paycheck within a week of starting.",
    "We work with major brands like Amazon and Walmart — all tasks are performed online from home.",
    "Your daily tasks involve rating products, completing surveys, and submitting reports — all paid weekly.",
    # Russian
    "Работа предполагает простой ввод данных из дома — всего два часа в день.",
    "Вы будете тайным покупателем — оценивайте магазины и оставляйте товары себе.",
    # Hebrew
    "התפקיד כולל הזנת נתונים פשוטה מהבית — רק שעתיים ביום.",
    "אתה תהיה קונה סמוי — תעריך חנויות ותשמור על המוצרים.",
    # Arabic
    "الوظيفة تتضمن إدخال بيانات بسيط من المنزل — ساعتان فقط يوميًا.",
    "ستكون متسوقًا سريًا — قيّم المتاجر واحتفظ بالبضائع.",
]

JOB_UPFRONT = [
    "We require a small registration fee of one hundred dollars to process your background check.",
    "Before we can proceed you need to pay for your training materials — a one-time fee of two hundred dollars.",
    "We need a refundable security deposit of five hundred dollars before issuing your work equipment.",
    "Please purchase our starter kit for one hundred and fifty dollars — you earn it back on your first assignment.",
    "To activate your account you need to purchase gift cards worth two hundred dollars and send the codes.",
    "There is a small processing fee for the work permit — please wire one hundred and fifty dollars.",
    "Your employment contract requires a bond payment of three hundred dollars — fully refundable after probation.",
    "We need your banking details to set up direct deposit and process your advance payment.",
    "Download our app and top up your account with fifty dollars to unlock job assignments.",
    "Please send your bank account information so we can process your sign-on bonus of one thousand dollars.",
    # Russian
    "Требуется небольшой регистрационный взнос в размере ста долларов для проверки биографии.",
    "Нужен возвращаемый залог в размере пятисот долларов за рабочее оборудование.",
    # Hebrew
    "נדרש תשלום רישום של מאה דולר לצורך בדיקת רקע לפני שנוכל להמשיך.",
    "יש להפקיד ערבות של חמש מאות דולר לפני קבלת ציוד העבודה.",
    # Arabic
    "يلزم دفع رسوم تسجيل بمبلغ مئة دولار لمعالجة فحص الخلفية.",
    "نحتاج وديعة أمان قابلة للاسترداد بمبلغ خمسمئة دولار قبل إصدار معدات العمل.",
]

JOB_FAKE_CHECK = [
    "We are sending you a check for three thousand dollars — deposit it and wire back two thousand for supplies.",
    "Your advance payment check is in the mail — once it clears transfer the excess back to our account.",
    "We overpaid your signing bonus — please wire back the difference immediately after depositing.",
    "Cash the check we sent and keep five hundred for yourself — send the remaining amount via gift cards.",
]

def make_job_rows(n):
    rows = []
    pools = [
        lambda: pick(JOB_OPENER) + " " + pick(JOB_DETAILS),
        lambda: pick(JOB_OPENER) + " " + pick(JOB_UPFRONT),
        lambda: pick(JOB_DETAILS) + " " + pick(JOB_UPFRONT),
        lambda: pick(JOB_OPENER) + " " + pick(JOB_DETAILS) + " " + pick(JOB_UPFRONT),
        lambda: pick(JOB_FAKE_CHECK),
        lambda: pick(JOB_OPENER) + " " + pick(JOB_FAKE_CHECK),
        lambda: pick(JOB_OPENER),
        lambda: pick(JOB_DETAILS) + " " + pick(JOB_FAKE_CHECK),
    ]
    for i in range(n):
        fn = pools[i % len(pools)]
        rows.append(row(fn(), 13))
    return rows

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
def main():
    out_path = pathlib.Path(__file__).parent / "training_data.csv"

    # Count existing rows per label
    existing = []
    with open(out_path, newline="", encoding="utf-8") as f:
        reader = csv.reader(f)
        header = next(reader)
        for r in reader:
            existing.append(r)

    from collections import Counter
    label_counts = Counter(r[1] for r in existing)
    print("Current label distribution:")
    for k in sorted(label_counts, key=int):
        print(f"  Label {k}: {label_counts[k]} rows")

    # Target: bring each of 9, 11, 12, 13 up to ~980
    TARGET = 980
    to_add = {
        9:  max(0, TARGET - label_counts.get("9", 0)),
        11: max(0, TARGET - label_counts.get("11", 0)),
        12: max(0, TARGET - label_counts.get("12", 0)),
        13: max(0, TARGET - label_counts.get("13", 0)),
    }
    print("\nWill add:")
    for k, v in to_add.items():
        print(f"  Label {k}: +{v} rows")

    new_rows = []
    new_rows.extend(make_investment_rows(to_add[9]))
    new_rows.extend(make_romance_rows(to_add[11]))
    new_rows.extend(make_delivery_rows(to_add[12]))
    new_rows.extend(make_job_rows(to_add[13]))

    random.shuffle(new_rows)

    with open(out_path, "a", newline="", encoding="utf-8") as f:
        writer = csv.writer(f, quoting=csv.QUOTE_MINIMAL)
        for r in new_rows:
            writer.writerow(r)

    total = len(existing) + len(new_rows)
    print(f"\nAdded {len(new_rows)} rows. Total: {total}")
    print("Done — now run: python train_model.py")

if __name__ == "__main__":
    main()
