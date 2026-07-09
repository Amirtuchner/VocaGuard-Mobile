#!/usr/bin/env python3
"""
Add training rows for the "social-media ad → soft trust-building call" investment scam.

Scammer behaviour:
  - References a Facebook/Instagram/social-media ad the victim interacted with
  - Uses victim's name, sounds calm, professional, trustworthy
  - No aggressive urgency in the first call — just rapport-building
  - Mentions guaranteed profits / capital protection from the ad
  - Positions themselves as a "financial consultant" / "investment advisor"
  - Follows up with: exclusive platform, small initial deposit, big returns

All rows map to label 9 (INVESTMENT_SCAM).
Safe to re-run — guard checks current count first.
"""
import csv, os, sys

CSV_PATH = os.path.join(os.path.dirname(__file__), "training_data.csv")

SOCIAL_INVESTMENT_ROWS = [
    # ── English: referencing the social-media ad ─────────────────────────────
    "Hi, I'm calling about the investment opportunity you registered for on our Facebook page last week",
    "Good morning, you left your contact details on our advertisement and I wanted to follow up personally",
    "Hello, you showed interest in our investment program online and I'm your dedicated financial consultant",
    "I saw that you filled in the form on our Instagram ad about guaranteed returns and I wanted to reach out",
    "You registered on our website after seeing our Facebook ad about the investment platform, correct?",
    "Hi, my name is David and I'm calling on behalf of our investment firm you saw advertised on social media",
    "Good afternoon, I noticed you left your details on our ad about the trading opportunity last week",
    "Hello, this is a follow-up call about the online investment program you expressed interest in",
    "You saw our advertisement about the investment opportunity and I wanted to explain it to you personally",
    "I'm reaching out because you registered your details on our platform after seeing our promoted post",
    "Hi, I'm your personal financial consultant from the investment firm you saw advertised on Facebook",
    "Good morning, you filled out our online form about the investment opportunity and I'd love to tell you more",
    "Hello, I'm following up on your interest in our trading platform from the ad you saw last week",
    "You signed up on our landing page after seeing our guaranteed returns advertisement, is that right?",
    "Hi, I'm calling because you left your name and number on our investment opportunity form online",

    # ── English: calm trust-building, professional tone ───────────────────────
    "I just wanted to introduce myself and answer any questions you might have about our investment program",
    "There is no pressure at all, I just want to explain how our platform works and see if it suits you",
    "Many of our clients started exactly where you are, just curious and wanting to understand more",
    "I understand you may be cautious and that is completely normal, I'm here to build your confidence",
    "Take your time, there is no rush, I just want to make sure you have all the information you need",
    "I'm not here to sell you anything today, I just want to walk you through how our clients earn",
    "Our clients make consistent profits every month and I'd love to show you how it works step by step",
    "We offer a fully managed account so you don't need any experience, our experts handle everything",
    "The returns are guaranteed because our analysts have been doing this for over ten years",
    "Your capital is always protected, you can withdraw at any time, there is no risk to your funds",
    "We have clients all over the world earning passive income from our platform every single month",
    "I would love to set up a small demonstration account for you so you can see the results yourself",
    "Many of our clients started with a small initial deposit and now earn thousands every month",
    "Our trading algorithm has a consistent success rate and your investment is always protected",
    "I can be your personal advisor and guide you through every step, you are never alone in this",

    # ── English: mixing social-media reference + investment pitch ─────────────
    "You saw the ad about guaranteed profits on Facebook and I can confirm those numbers are real",
    "The advertisement you saw on Instagram about our platform is completely accurate, clients do earn that much",
    "The returns you saw advertised on social media are real and I can show you proof from our existing clients",
    "Our Facebook ad mentioned thirty percent monthly returns and I can explain exactly how that is achieved",
    "You saw the promoted post about our investment platform and I am here to give you the full details",
    "The ad you saw on social media about financial freedom is exactly what we offer our select clients",
    "Our Instagram promotion caught your attention and I want to make sure you fully understand the opportunity",
    "You noticed our advertisement about passive income and I want to be transparent about exactly how it works",
    "The guaranteed profits you saw in our ad are achieved through our proprietary trading algorithm",
    "Our social media promotion reached you and I believe you could be a great fit for our investor community",

    # ── Hebrew: social media reference + soft pitch ───────────────────────────
    "שלום, אני מתקשר בנוגע להשקעה שנרשמת אליה דרך הפרסומת שלנו בפייסבוק השבוע",
    "בוקר טוב, ראיתי שהשארת פרטים בטופס שלנו אחרי שצפית במודעה שלנו על תשואות מובטחות",
    "היי, אני היועץ הפיננסי האישי שלך מחברת ההשקעות שראית בפרסומת ברשתות החברתיות",
    "ראיתי שמילאת את הטופס באתר שלנו אחרי שראית את הפרסומת שלנו באינסטגרם על רווחים מובטחים",
    "שלום, אני מתקשר כי הבעת עניין בפלטפורמת המסחר שלנו דרך הפרסומת בפייסבוק",
    "אני רוצה להסביר לך אישית את הפרטים על ההזדמנות שראית בפרסומת שלנו ברשת",
    "אין שום לחץ, אני רק רוצה לוודא שיש לך את כל המידע על תוכנית ההשקעה שלנו",
    "ההון שלך תמיד מוגן ואתה יכול למשוך בכל עת, אין שום סיכון לכספך",
    "הרווחים המובטחים שראית בפרסומת הם אמיתיים ויש לי הוכחות מלקוחות קיימים",
    "אני אהיה היועץ האישי שלך ואדריך אותך בכל שלב, לעולם לא תהיה לבד בתהליך",
    "לקוחות רבים התחילו בדיוק כמוך, סקרנים ורוצים להבין יותר, ועכשיו מרוויחים בכל חודש",
    "הפלטפורמה שלנו מנוהלת על ידי מומחים ואתה לא צריך שום ניסיון כדי להרוויח",
    "ראיתי שהתעניינת בתוכנית ההשקעה שלנו ברשתות החברתיות ואני רוצה לתת לך פרטים נוספים",
    "החברה שלנו מציעה חשבון מנוהל עם תשואה חודשית קבועה וההון שלך מוגן לחלוטין",
    "הרבה מלקוחותינו התחילו עם סכום קטן ועכשיו מרוויחים אלפי שקלים כל חודש",

    # ── Hebrew: trust-building phrases ───────────────────────────────────────
    "אין לחץ בכלל, אני רק רוצה שתבין איך הפלטפורמה עובדת לפני שתחליט",
    "אני כאן כדי לענות על כל שאלה שיש לך ולבנות אמון לפני שנעשה כל צעד",
    "הרבה לקוחות שלנו היו סקפטים בהתחלה ועכשיו הם מרוויחים תשואות עקביות בכל חודש",
    "התוכנית שלנו מתאימה לאנשים שרוצים הכנסה פסיבית בלי ניסיון קודם בשוק ההון",
    "אני יועץ פיננסי מוסמך ואני כאן כדי לוודא שההשקעה שלך בטוחה ומניבה",

    # ── Russian ───────────────────────────────────────────────────────────────
    "Здравствуйте, я звоню по поводу инвестиционной возможности которую вы нашли через нашу рекламу в Facebook",
    "Добрый день вы оставили свои контактные данные на нашем объявлении и я хотел бы рассказать подробнее",
    "Вы видели нашу рекламу о гарантированной прибыли в Instagram и я ваш личный финансовый консультант",
    "Нет никакого давления я просто хочу объяснить как работает наша платформа шаг за шагом",
    "Ваш капитал всегда защищён и вы можете вывести средства в любое время без каких-либо рисков",
    "Многие наши клиенты начали с небольшого депозита и теперь зарабатывают тысячи каждый месяц",
    "Я буду вашим личным советником и буду сопровождать вас на каждом этапе инвестирования",

    # ── Arabic ────────────────────────────────────────────────────────────────
    "مرحباً أنا أتصل بشأن فرصة الاستثمار التي سجلت اهتمامك بها عبر إعلاننا على فيسبوك",
    "صباح الخير لقد تركت بياناتك في نموذجنا بعد مشاهدة إعلاننا عن العوائد المضمونة",
    "لا يوجد أي ضغط أنا فقط أريد أن أشرح لك كيف تعمل منصتنا خطوة بخطوة",
    "رأس مالك محمي دائماً ويمكنك السحب في أي وقت دون أي مخاطر",
    "كثير من عملائنا بدأوا بإيداع صغير والآن يكسبون آلاف الدولارات كل شهر",
    "أنا مستشارك المالي الشخصي وسأرشدك في كل خطوة من خطوات الاستثمار",

    # ── Spanish ───────────────────────────────────────────────────────────────
    "Hola llamo por la oportunidad de inversión que registró a través de nuestro anuncio en Facebook",
    "Buenos días usted dejó sus datos en nuestro formulario después de ver nuestro anuncio en Instagram",
    "No hay ninguna presión solo quiero explicarle cómo funciona nuestra plataforma paso a paso",
    "Su capital está siempre protegido y puede retirar en cualquier momento sin ningún riesgo",
    "Muchos de nuestros clientes empezaron con un pequeño depósito y ahora ganan miles cada mes",

    # ── French ────────────────────────────────────────────────────────────────
    "Bonjour je vous appelle au sujet de l'opportunité d'investissement que vous avez vue sur notre publicité Facebook",
    "Bonjour vous avez laissé vos coordonnées dans notre formulaire après avoir vu notre annonce Instagram",
    "Il n'y a aucune pression je veux juste vous expliquer comment fonctionne notre plateforme étape par étape",
    "Votre capital est toujours protégé et vous pouvez retirer à tout moment sans aucun risque",
    "Beaucoup de nos clients ont commencé avec un petit dépôt et gagnent maintenant des milliers chaque mois",
]

def main():
    if not os.path.exists(CSV_PATH):
        print(f"ERROR: {CSV_PATH} not found")
        sys.exit(1)

    with open(CSV_PATH, newline='', encoding='utf-8') as f:
        reader = csv.DictReader(f)
        rows = list(reader)
        fieldnames = reader.fieldnames

    label9_count = sum(1 for r in rows if r['label'] == '9')
    print(f"Current label 9 (Investment Scam) count: {label9_count}")

    if label9_count > 350:
        print("Label 9 already has >350 rows. Skipping to avoid duplicates.")
        return

    new_rows = [{'text': t.strip(), 'label': '9'} for t in SOCIAL_INVESTMENT_ROWS]
    print(f"Appending {len(new_rows)} new investment scam rows (social-media ad pattern)...")

    with open(CSV_PATH, 'a', newline='', encoding='utf-8') as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames, extrasaction='ignore')
        for row in new_rows:
            full_row = {col: '' for col in fieldnames}
            full_row['text'] = row['text']
            full_row['label'] = row['label']
            writer.writerow(full_row)

    with open(CSV_PATH, newline='', encoding='utf-8') as f:
        total = sum(1 for _ in csv.DictReader(f))
    label9_new = sum(1 for r in csv.DictReader(open(CSV_PATH, encoding='utf-8')) if r['label'] == '9')
    print(f"Done. Total rows: {total} | Label 9: {label9_new}")

if __name__ == '__main__':
    main()
