#!/usr/bin/env python3
"""
Generate targeted training rows for labels 11 (romance), 12 (delivery), 13 (job).
Each row is designed to activate the new exclusive features (47, 48, 49) while
minimising overlap with existing category features that caused gradient collapse.

Appends rows to training_data.csv. Safe to re-run only once — check existing count first.
"""
import csv
import sys
import os

CSV_PATH = os.path.join(os.path.dirname(__file__), "training_data.csv")

# ---------------------------------------------------------------------------
# Label 11: Romance / pig-butchering / grandparent scam
# Target features: 43 (romance), 47 (emotional intimacy / grandparent emergency)
# Avoid: feature 21 (investment) in rows not about pig-butchering
# ---------------------------------------------------------------------------
ROMANCE_ROWS = [
    # --- Grandparent emergency scam ---
    "Grandma it's me I got into a car accident last night and I need bail money please don't tell mom",
    "Hi grandpa it's your grandson I'm in the hospital after an accident I need you to wire me some money",
    "Grandma it's me I'm in trouble with the police I need bail money please send it quickly don't tell anyone",
    "It's me your granddaughter I had an accident abroad I need money for the hospital bill please help",
    "Grandpa I'm stuck in jail overseas I need bail money the lawyers say you need to wire it today",
    "Grandma I'm calling from the hospital I was in a car accident and I need two thousand dollars for surgery",
    "It's your grandson I got arrested at the border they need bail money to let me go please don't tell dad",
    "Hi nana it's me I'm stranded abroad my wallet was stolen and I need you to send me some money",
    "Grandpa this is an emergency I had a car accident and the hospital won't treat me without a deposit",
    "It's me grandma I'm in trouble overseas I need bail money right away please don't tell mom or dad",
    "Hello I'm calling on behalf of your grandson he was in a car accident and urgently needs medical funds",
    "Your granddaughter has been arrested at customs she needs bail money wired immediately",
    "It's me your grandson I borrowed money from someone and they are threatening me please send cash",
    "Grandma I'm calling from a friend's phone I broke mine in the accident I need you to send money now",
    "Hi grandpa I was mugged in another city I have no money for a hotel or a ticket home please help",
    "It's your grandson I am in the hospital after a car accident I need two thousand for surgery do not tell mom",
    "Grandma it's me please don't panic I'm okay but I need bail money for a misunderstanding at the border",
    "Grandpa please don't tell anyone but I'm in the hospital I had a car accident please wire me money",
    "It's your granddaughter I am stranded in another country and need emergency funds please help me",
    "Hi grandma I had an accident last night I am at the hospital and I need you to send money quickly",
    # --- Pure romance / love-bomb scam ---
    "I found your profile on the dating site and I think you are so beautiful I would love to get to know you",
    "I have been thinking about you all day since we matched on the app you seem like my soulmate",
    "I love you so much my darling I miss you every day I hope we can video call tonight",
    "You are the most amazing person I have met online I feel like I have known you my whole life",
    "My sweetheart I miss you terribly I cannot wait until we can meet in person someday",
    "I have been looking at your photos all day you are so beautiful I think I am falling in love with you",
    "We have been talking for three months now and I trust you completely you are my dearest friend",
    "I have never felt this way about someone I met online you are so special to me my love",
    "I want to send you a gift but I need your address first our connection is so special to me",
    "You are the first person who truly understands me since I saw your profile I knew you were different",
    "My darling I feel such a deep connection with you our video calls make me so happy every day",
    "I matched with you on the dating app and I cannot stop thinking about you please respond",
    "Hello beautiful I saw your profile and I think we would be perfect together please add me on whatsapp",
    "I feel like we have a special connection I have never opened up to anyone like this before",
    "I trust you more than anyone I have ever met online I want to share something important with you",
    "My dearest I have been working abroad for many years and I am so lonely I am so glad I found you",
    "You are my soulmate I knew it from the first message we are meant to be together I just know it",
    "I have strong feelings for you we have been talking for weeks and I want our relationship to grow",
    "My love I miss you so much video calling is not enough I want to meet you in person soon",
    "I have never been this happy since I started chatting with you on the dating app",
    "Hello I saw you on tinder and thought we had a real connection I would love to chat more",
    "We matched on bumble last week and I think about you constantly please let us talk more",
    "I found you on a dating site and felt an instant connection I hope we can get to know each other",
    "I am a military officer stationed abroad and I found your profile online I hope we can be friends",
    "I am a doctor working in a conflict zone and I found you online you seem like a wonderful person",
    "I am an engineer on an oil rig overseas and I found your profile I feel very lonely here",
    "I work as a surgeon abroad and I am very lonely I saw your beautiful photos and wanted to connect",
    "I am a widower and I have been very lonely until I found you on the dating app",
    "I lost my wife two years ago and I am trying to move on meeting you online has been wonderful",
    "I am a successful businessman but I am very lonely I think you could be my missing piece",
    "My dear I know we only met online but my feelings for you are very real I am falling in love",
    "I have never felt this connected to someone so quickly you are very special to me I love you already",
    "Please trust me when I say I have never felt this way about someone I met on the internet before",
    "You are everything I have been looking for I cannot believe I found you on this dating app",
    "I cannot stop smiling since we started chatting I think you could be the one for me",
    "I have been chatting with you for months now and I feel like I really know you I trust you completely",
    "You are my best friend and my love you have changed my life since we connected on the dating site",
    "My darling thinking of you keeps me going I look at your profile picture every day",
    "I am so in love with you my dear I hope we can video call again tonight I miss you so much",
    "You are so beautiful and kind I knew from the first moment I saw your profile that you were special",
    # --- Pig-butchering (romance + investment, still label 11) ---
    "My darling I miss you I want to show you the trading platform that changed my life",
    "I love you sweetheart and I want us to build a future together I have been earning on this trading platform",
    "You are so special to me my love and I want to share my trading secret with you it is very profitable",
    "We have such a beautiful relationship and I want to help you financially this crypto platform is amazing",
    "My darling since we matched on the dating app I fell in love and I also found this amazing investment",
    "I love chatting with you every day my dear and I want to show you how I earn passive income online",
    "Our relationship means everything to me and I want us to invest together on this platform I trust you",
    "I miss you so much and I want to share something that changed my life financially trust me on this",
    "You are my soulmate and I want to help you earn money the platform my uncle manages it",
    "I love you dearly and I also want to share the investment opportunity that changed my financial life",
    # --- Multilingual romance rows ---
    "Cariño te echo de menos todos los días desde que nos conocimos en la aplicación de citas",
    "Mi amor me enamoré de ti desde que vi tu perfil eres lo más especial que me ha pasado",
    "Soy un médico que trabaja en el extranjero y encontré tu perfil en línea espero que podamos ser amigos",
    "Abuela soy yo tuve un accidente de coche y necesito dinero para la fianza por favor no le digas a mamá",
    "Mon amour je pense à toi tous les jours depuis que nous nous sommes rencontrés sur l'application de rencontres",
    "Je t'aime tellement ma chérie tu me manques j'espère qu'on pourra faire un appel vidéo ce soir",
    "Grand-mère c'est moi j'ai eu un accident de voiture j'ai besoin d'une caution s'il te plaît n'en parle pas à maman",
    "Я люблю тебя моя дорогая скучаю по тебе каждый день с тех пор как мы познакомились на сайте знакомств",
    "Бабушка это я я попал в аварию и мне нужен залог пожалуйста не говори маме",
    "אני אוהב אותך יקירתי מתגעגע אליך כל יום מאז שהתחלנו לדבר באפליקציית היכרויות",
    "סבתא זה אני הייתה לי תאונה אני בבית חולים צריך כסף לא תגידי לאמא",
]

# ---------------------------------------------------------------------------
# Label 12: Delivery / package scam
# Target features: 44 (delivery), 48 (customs fee / redelivery demand)
# ---------------------------------------------------------------------------
DELIVERY_ROWS = [
    # --- Customs clearance fee ---
    "Your package is being held at customs a clearance fee of thirty five dollars must be paid to release it today",
    "This is a notification from FedEx your shipment is on hold at the border please pay customs clearance fee immediately",
    "DHL alert your parcel requires payment of import duty before it can be released from the customs warehouse",
    "Your UPS package has been stopped at customs you must pay a clearance charge of forty five dollars online",
    "USPS notice your package is held at the post office a small customs fee is required before delivery",
    "Your Amazon package could not be delivered without paying a customs processing fee of twenty dollars",
    "Your international parcel is waiting at customs please pay the import duty to avoid it being returned",
    "Your package from overseas has been detained at the border pay the customs tax to have it delivered today",
    "Notice your shipment is on hold due to unpaid customs duty of eighteen dollars please pay immediately",
    "Your package requires a customs clearance payment of twenty five dollars please click to pay now",
    "Your shipment arrived but cannot proceed through customs without paying the required clearance fee",
    "Your parcel from abroad is being held at the customs office please pay the outstanding import fee",
    "FedEx customs alert your package is at the border facility pay the small import duty to release it",
    "DHL international notice your shipment is pending customs release payment of twenty dollars required",
    "UPS notification your package is detained at customs please settle the clearance fee online immediately",
    "Your package has incurred a customs fee of fifteen dollars please pay to release your shipment",
    "We are holding your parcel at customs pending payment of the import duty fee of thirty dollars",
    "Your tracked shipment requires customs clearance fee payment before it can be forwarded to your address",
    "A package in your name is being held at customs due to an outstanding customs charge please pay now",
    "Your international delivery is on hold at the border checkpoint please pay the customs tax to proceed",
    "Customs alert your parcel from overseas cannot be delivered until the import duty of twenty five dollars is paid",
    "Your package is waiting at the customs facility please pay the clearance fee of forty dollars to release it",
    "We were unable to clear your package through customs without payment of the required import fee",
    "Your shipment is detained at the border please use the link below to pay the customs duty fee",
    "Final customs notice your package will be returned to the sender unless you pay the clearance fee today",
    # --- Redelivery / address update ---
    "Your delivery was attempted but we could not find your address please update your details and pay a redelivery fee",
    "We were unable to deliver your parcel please reschedule delivery and pay a small redelivery charge of two dollars",
    "Your package could not be delivered please click the link to update your address and pay the redelivery fee",
    "Delivery attempt failed for your order please pay a redelivery fee of one ninety nine to reschedule",
    "Your item was returned to the post office because the address was undeliverable please pay to reschedule delivery",
    "We could not deliver your package the address provided was incomplete please pay two dollars to rearrange",
    "Your parcel is waiting at our depot please pay a small storage fee to have it redelivered to your address",
    "Final delivery notice your package will be returned to sender if you do not pay the redelivery fee today",
    "Your order has been held because the delivery address could not be confirmed please pay to update and redeliver",
    "We attempted delivery but no one was home your parcel will be returned unless you pay to reschedule",
    "Your shipment is pending delivery please confirm your address and pay the nominal redelivery fee",
    "Your Amazon package is at our local depot pay a small fee to have it delivered to your doorstep today",
    "Your USPS parcel requires address confirmation and a small processing fee before final delivery",
    "Your package is being held for pickup at the post office please pay the storage fee to claim it",
    "Urgent your package will be sent back to the sender in twenty four hours please pay the release fee now",
    # --- General delivery scam ---
    "This is an automated notification your package is ready for delivery pending payment of a small fee",
    "Your international order has arrived at our facility but requires customs clearance before delivery",
    "We have your package but need you to verify your details and pay a small fee to complete delivery",
    "Your tracked shipment shows a problem at customs please visit our website and pay the required duty",
    "You have a package waiting but it requires a clearance fee of fifteen dollars before it can be delivered",
    "Your online order is stuck at the distribution center please pay a small fee to resume delivery",
    "Notice from the postal service your package has an outstanding fee that must be paid before delivery",
    "Your delivery could not proceed due to an unpaid customs fee please settle the amount online",
    "This is Royal Mail your parcel requires a customs charge of eight pounds before it can be delivered",
    "Your TNT shipment is on hold at customs please pay the import fee of thirty dollars to proceed",
    "We tried to deliver your package but it is now at a customs checkpoint requiring fee payment",
    "Your package is stuck at the customs border please use the link to pay the small customs fee",
    "Attention your parcel from overseas requires import fee payment before dispatch to your address",
    "Your shipment is held at customs please contact us and pay the clearance fee of twenty dollars",
    "A package addressed to you is waiting at the customs office please pay the processing fee today",
    "Your FedEx package held at customs requires an import duty payment of thirty five dollars to be released",
    "DHL customs hold your package is at the border and requires a clearance fee before onward delivery",
    "Your USPS shipment is detained at customs please pay the small fee to have your package delivered",
    "Your parcel from abroad is awaiting customs clearance please pay the duty fee of twenty dollars",
    "Delivery failed your package is being held at the post office please pay the redelivery charge",
    # --- Multilingual delivery rows ---
    "Votre colis est retenu en douane veuillez payer les frais de douane de vingt euros pour le libérer",
    "Alerte DHL votre envoi nécessite le paiement de droits de douane avant d'être libéré du bureau des douanes",
    "Votre paquet FedEx est bloqué à la frontière payez les frais de libération pour recevoir votre colis",
    "Ваша посылка задержана на таможне оплатите таможенную пошлину в размере пятнадцати долларов для получения",
    "Уведомление DHL ваша посылка требует оплаты сбора за растаможку перед тем как её можно забрать",
    "הודעה מדהל החבילה שלך עצורה במכס יש לשלם אגרת מכס של עשרים דולר לשחרורה",
    "הודעה מפדקס החבילה שלך ממתינה במכס יש לשלם עמלת שחרור לפני משלוח",
    "إشعار FedEx طردك محتجز في الجمارك يرجى دفع رسوم التخليص الجمركي لاستلامه",
    "إشعار DHL شحنتك تتطلب دفع رسوم الجمارك قبل الإفراج عنها",
    "Su paquete está retenido en aduanas debe pagar el arancel aduanero de treinta dólares para liberarlo",
    "Alerta FedEx su envío está detenido en la frontera por favor pague los derechos de aduana para continuar",
]

# ---------------------------------------------------------------------------
# Label 13: Job / recruitment scam
# Target features: 45 (job), 49 (upfront fee / task-earning)
# ---------------------------------------------------------------------------
JOB_ROWS = [
    # --- Training fee / upfront payment ---
    "We are offering you a work from home position but first you must pay a training deposit of two hundred dollars",
    "Congratulations you are hired for our remote data entry team please pay the registration fee to start",
    "You have been selected for this job opportunity to begin you must pay a starter kit fee of one fifty",
    "This remote customer service job requires a background check fee of seventy five dollars to proceed",
    "Your application was approved you must pay a small training fee of one hundred dollars to access the materials",
    "We found your resume online and want to hire you but you need to pay a refundable deposit first",
    "Congratulations on being selected for this remote position please pay the equipment deposit of two fifty",
    "This work from home job pays well but requires a starter kit fee of one hundred and fifty dollars",
    "You have been hired please send the registration fee of eighty dollars to receive your training materials",
    "To begin your remote work position please pay the onboarding fee of one hundred dollars immediately",
    "Your application was successful you need to pay a background check fee before your start date",
    "We are hiring you for this data entry role but require a small security deposit to issue your equipment",
    "You have been selected for a home based job please pay one twenty five for the starter materials",
    "To start your new remote position we need a processing fee to start of ninety dollars from you today",
    "We would like to hire you for this remote position but first pay the refundable deposit of one fifty",
    "Congratulations you passed the screening to start working you must pay the training deposit of eighty dollars",
    "We are excited to offer you this work from home role please pay the registration fee to receive your kit",
    "You have been shortlisted for our home based job please pay the onboarding fee to get started",
    "We are ready to hire you for this remote position please pay the security deposit to confirm your spot",
    "Your remote job application was accepted please pay the small processing fee to start orientation",
    # --- Task / micro-earning scam ---
    "Join our team and complete simple tasks online earn up to fifty dollars per task no experience needed",
    "We are looking for app reviewers earn twenty dollars per review complete tasks from your phone at home",
    "Earn money by boosting products on our platform complete simple tasks and get paid daily",
    "Join our telegram task channel and complete daily tasks to earn cash no skills required start today",
    "We pay you to like and rate products on our platform earn up to two hundred dollars per day",
    "Mystery shopper opportunity earn fifty dollars per shop no experience needed apply now",
    "Complete product reviews and earn money from home our tasks take only minutes and pay well",
    "We hire people to boost our app ratings earn ten dollars per review work at your own pace",
    "Our task completion platform pays workers daily complete simple app reviews and earn fast cash",
    "Earn per task on our platform no experience or special skills needed just a phone and internet",
    "Join thousands who earn from home completing easy tasks like reviews and surveys for good pay",
    "We need product testers to earn extra income complete simple tasks on our app daily",
    "Our platform pays users to complete tasks like liking posts and rating apps earn up to three hundred daily",
    "Be a mystery shopping agent earn cash for visiting stores and filling out short surveys apply today",
    "Complete online tasks and get paid to your account daily our members earn hundreds per week",
    "Earn from home by completing micro tasks such as app reviews and product ratings paid daily",
    "We are hiring task workers to rate apps and boost our product listings earn ten dollars per task",
    "Our mystery shopper program pays well you visit a store buy a product and rate your experience",
    "Join our product reviewer team complete simple tasks online and earn per task every day",
    "Boosting task opportunity earn money by liking products and writing short reviews on our app",
    # --- Reshipping / money mule ---
    "We are looking for reshipping agents to process packages from home no experience needed good pay",
    "Work as a parcel forwarding agent receive packages and ship them on we pay per shipment",
    "Money transfer agent needed work from home receive and forward funds earn a commission per transfer",
    "We are hiring package receiving agents work from home receive parcels and reship them for payment",
    "Financial processing agent needed receive money transfers into your account and forward them on",
    "Be our local distribution partner receive packages at home and reship them earn two fifty per package",
    "Package forwarding agent wanted work from home receive packages and ship abroad for good commission",
    "We need trusted agents to receive bank transfers and resend them you keep the commission of fifteen percent",
    "Reshipping coordinator needed work at home receive packages and forward them to our clients",
    "Be a work from home shipping agent receive parcels and deliver them to local addresses earn well",
    # --- General job scam rows ---
    "Congratulations your resume was selected for a work from home position earning five hundred per week",
    "We are hiring remote workers no experience needed you can earn up to two thousand dollars per month",
    "We found your CV online and we have an exciting part time job opportunity that pays very well",
    "You have been selected for a remote customer service position start immediately earn eight hundred per week",
    "Data entry job available from home no experience needed earn one thousand dollars per week",
    "We are recruiting home based workers to process online orders earn up to three thousand monthly",
    "Exciting remote work opportunity earn fifteen hundred per week doing simple administrative tasks",
    "Work from home and earn big we are hiring data processors with no experience required",
    "Your profile was shortlisted for our remote work program earn up to five hundred per week",
    "Part time remote work available earn supplemental income from home doing simple tasks online",
    "We are urgently hiring home workers to help with our online business start earning today",
    "Remote customer service agents needed work from home earn well with flexible schedule",
    "Your CV was found on a job site we want to offer you a well paid work from home position",
    "Home based job opportunity earn two thousand per month working just a few hours per day",
    "Earn from home by helping small businesses with their social media earn five hundred per week",
    # --- Multilingual job rows ---
    "Nous recrutons des travailleurs à domicile aucune expérience nécessaire gagnez jusqu'à deux mille euros par mois",
    "Offre d'emploi travail à domicile nous recherchons des agents de réexpédition pour traiter des colis",
    "Félicitations votre CV a été sélectionné pour un poste à distance gagnez cinq cents euros par semaine",
    "Мы ищем сотрудников для удалённой работы без опыта зарабатывайте до двух тысяч долларов в месяц",
    "Вакансия агента по пересылке пакетов работа из дома получайте посылки и пересылайте их вознаграждение за каждую",
    "הצעת עבודה עבודה מהבית גיוס עובדים ללא ניסיון הרוויח עד אלפיים שקל בשבוע",
    "אנחנו מחפשים סוכני שליחויות עבודה מהבית קבל חבילות ושלח אותן הלאה דמי הכשרה ניתנים להחזר",
    "عرض عمل عمل من المنزل نحن نوظف دون خبرة اكسب ما يصل إلى ألفي دولار شهريًا",
    "فرصة عمل من المنزل نحن نبحث عن وكلاء إعادة شحن لمعالجة الطرود احصل على عمولة لكل شحنة",
    "Oferta de trabajo desde casa contratación sin experiencia gana hasta dos mil dólares al mes",
    "Agente de reenvío de paquetes necesario trabaja desde casa recibe paquetes y reenvíalos gana por envío",
]


def main():
    if not os.path.exists(CSV_PATH):
        print(f"ERROR: {CSV_PATH} not found")
        sys.exit(1)

    # Read existing data to check current counts
    with open(CSV_PATH, newline='', encoding='utf-8') as f:
        reader = csv.DictReader(f)
        rows = list(reader)
        fieldnames = reader.fieldnames

    counts = {}
    for row in rows:
        label = int(row['label'])
        counts[label] = counts.get(label, 0) + 1

    print(f"Current counts for labels 11/12/13:")
    for lbl in [11, 12, 13]:
        print(f"  Label {lbl}: {counts.get(lbl, 0)} rows")

    # Guard: don't double-append
    if counts.get(11, 0) > 200 and counts.get(12, 0) > 200 and counts.get(13, 0) > 200:
        print("Labels 11/12/13 already have >200 rows each. Skipping to avoid duplicates.")
        return

    new_rows = []
    for text in ROMANCE_ROWS:
        new_rows.append({'text': text.strip(), 'label': '11'})
    for text in DELIVERY_ROWS:
        new_rows.append({'text': text.strip(), 'label': '12'})
    for text in JOB_ROWS:
        new_rows.append({'text': text.strip(), 'label': '13'})

    print(f"\nAppending {len(new_rows)} rows ({len(ROMANCE_ROWS)} romance, {len(DELIVERY_ROWS)} delivery, {len(JOB_ROWS)} job)")

    # Append — preserve all existing columns, default new to empty string
    with open(CSV_PATH, 'a', newline='', encoding='utf-8') as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames, extrasaction='ignore')
        for row in new_rows:
            full_row = {col: '' for col in fieldnames}
            full_row['text'] = row['text']
            full_row['label'] = row['label']
            writer.writerow(full_row)

    # Final count
    with open(CSV_PATH, newline='', encoding='utf-8') as f:
        total = sum(1 for _ in csv.DictReader(f))
    print(f"\nDone. Total rows in training_data.csv: {total}")
    for lbl, name in [(11, 'Romance'), (12, 'Delivery'), (13, 'Job')]:
        with open(CSV_PATH, newline='', encoding='utf-8') as f:
            n = sum(1 for r in csv.DictReader(f) if r['label'] == str(lbl))
        print(f"  Label {lbl} ({name}): {n} rows")


if __name__ == '__main__':
    main()
