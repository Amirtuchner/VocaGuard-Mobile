#!/usr/bin/env python3
"""
Add future/emerging scam scenario training data to training_data.csv.

New labels:
  11 - Romance / pig-butchering / grandparent scam (AI voice cloning)
  12 - Delivery / package scam (FedEx, DHL, customs fee)
  13 - Job / recruitment scam (work from home, money mule, training fee)

Also adds rows expanding existing categories with newer attack patterns:
  1  - Utility shutoff, government benefit (stimulus, COVID relief)
  2  - AI chatbot tech support, "unusual login" threats
  3  - AI voice cloning bank fraud
  7  - SIM swap social engineering
  9  - Crypto recovery scam, NFT scam
"""

import csv
import os
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
CSV_PATH = os.path.join(SCRIPT_DIR, "training_data.csv")

FIELDNAMES = [
    "text", "label",
    "avg_rms", "rms_std_dev", "silence_ratio", "had_long_silence",
    "call_duration_sec", "call_hour",
    "speaker_switches", "noise_floor_db", "speech_rate_wpm", "dtmf_detected",
]


def row(text, label):
    return {
        "text": text, "label": label,
        "avg_rms": 0, "rms_std_dev": 0, "silence_ratio": 0, "had_long_silence": 0,
        "call_duration_sec": 0, "call_hour": 12,
        "speaker_switches": 0, "noise_floor_db": 0, "speech_rate_wpm": 0, "dtmf_detected": 0,
    }


# ---------------------------------------------------------------------------
# Label 11 — Romance / Pig-Butchering / Grandparent scam
# ---------------------------------------------------------------------------
ROMANCE = [
    # --- English: Pig-butchering / crypto investment romance ---
    row("Hi I'm Linda we matched on the app yesterday I'd love to get to know you better", 11),
    row("I met someone online and we've been talking for weeks she introduced me to her investment platform", 11),
    row("My friend told me about a crypto trading platform where I doubled my money you should try it", 11),
    row("We've been chatting for months and I feel like I really know you I want to show you how I invest", 11),
    row("I found this amazing trading platform my uncle manages it the returns are incredible trust me", 11),
    row("I know we just met online but I really trust you let me introduce you to my financial advisor", 11),
    row("This investment platform has guaranteed returns I've already made fifty thousand dollars this month", 11),
    row("I want to share my investment secret with you because I care about you just deposit five thousand to start", 11),
    row("My darling I need you to move your savings to this crypto platform before the window closes tonight", 11),
    row("We have such a special connection I want us to build our future together on this trading platform", 11),
    row("I've been trading on this platform for six months the profits are real I can show you my account", 11),
    row("Baby the investment platform is closing new registrations tonight you need to act now to join me", 11),
    row("My love I need your help I'm stuck abroad and my wallet was stolen please wire me money immediately", 11),
    row("This is a long distance relationship but I promise the investment opportunity I'm sharing is real", 11),
    row("I fell in love with you and I want to take care of you financially this platform will change your life", 11),
    row("We met on the dating app three weeks ago and I already know you are my soulmate invest with me", 11),
    row("The platform shows guaranteed profit every week just send your crypto to this wallet address my darling", 11),
    row("I want you to meet my uncle he manages a private investment fund only for people I trust like you", 11),
    row("Our relationship is real but I need you to withdraw your savings and send it so we can invest together", 11),
    row("I know it feels fast but this trading window only opens once a year we need to invest together now", 11),
    # --- English: Grandparent / AI voice cloning ---
    row("Grandma it's me I'm in jail and I need bail money please don't tell mom and dad", 11),
    row("Hi grandpa it's your grandson I got into an accident I'm in the hospital please send money", 11),
    row("Grandma I'm stuck abroad my passport was stolen I need you to wire five hundred dollars now", 11),
    row("This is your grandson please don't panic I was arrested overseas I need bail money tonight", 11),
    row("Grandpa it's me please keep this between us I need two thousand dollars right away I'm in trouble", 11),
    row("Hi nana I got mugged all my cards were stolen can you wire me money I'll explain everything later", 11),
    row("Grandma please don't call my parents I'm embarrassed but I need you to help me with bail money", 11),
    row("I'm your granddaughter I was in a car accident the other driver is suing me I need money urgently", 11),
    row("Grandpa it's me I know this sounds strange but I'm calling from a friend's phone please wire me money", 11),
    row("Hi this is a lawyer calling on behalf of your grandson he needs bail posted immediately call this number", 11),
    row("Your grandchild has been arrested and needs you to send gift cards to cover the bond please act now", 11),
    row("Grandma I need you to go to the store and buy iTunes gift cards I'll explain when I get home please hurry", 11),
    # --- Hebrew ---
    row("סבתא זה אני נעצרתי ואני צריך כסף לערבות אל תגידי לאמא ולאבא בבקשה", 11),
    row("סבא הכרתי מישהי אונליין והיא מציעה לי להשקיע יחד בפלטפורמה מיוחדת", 11),
    row("נפגשנו בהיכרויות לפני חודשיים ואני רוצה שתדע שאני רציני אגלה לך איך להרוויח כמוני", 11),
    row("סבתא אני בחוץ לארץ ואיבדתי את הארנק שלח לי כסף בבקשה אסביר הכל אחר כך", 11),
    row("מצאתי פלטפורמת מסחר מדהימה הרווחתי עשרת אלפים שקל החודש תשקיעי איתי", 11),
    row("היחסים שלנו אמיתיים ואני רוצה לבנות איתך עתיד בוא נשקיע ביחד בקריפטו", 11),
    row("אני קמעונאי בסין ומכרנו ביחד כבר חצי שנה עכשיו אני רוצה להציג לך הזדמנות השקעה", 11),
    row("סבא זה אני נלכדתי בתאונה ואני צריך כסף לא תגיד לאמא", 11),
    row("ביחד נשקיע בקריפטו הפלטפורמה הזאת מבטיחה תשואה של עשרים אחוז בחודש", 11),
    row("הכרתי בן אדם אונליין הוא אמר שיש לו שיטת השקעה בטוחה עם תשואות גבוהות מאוד", 11),
    # --- Arabic ---
    row("جدتي أنا في السجن أحتاج مال للكفالة لا تخبري أمي من فضلك", 11),
    row("تعرفت على شخص عبر الإنترنت وهو يريد أن يشركني في منصة استثمار رائعة", 11),
    row("علاقتنا حقيقية وأريد مستقبلاً معك دعونا نستثمر معاً في العملات المشفرة", 11),
    row("جدي أنا في ورطة كبيرة أحتاج مساعدتك أرسل لي مال الآن", 11),
    row("قابلتك عبر الإنترنت ووثقت بك أريد أن أشاركك سر ثروتي", 11),
    row("لا تخبر أمي أنا اعتقلت في الخارج وأحتاج مال للكفالة", 11),
    row("منصة الاستثمار هذه مضمونة أرباح شهرية عالية جداً اختي تثق بي", 11),
    row("وقعت في حب شخص على الإنترنت وهو قدم لي منصة تداول عملات مشفرة رائعة", 11),
    # --- Spanish ---
    row("Abuela soy yo estoy en la cárcel necesito dinero para la fianza no le digas a mamá", 11),
    row("Conocí a alguien en línea y me presentó una plataforma de inversión increíble", 11),
    row("Mi amor estoy atrapado en el extranjero perdí mi billetera mándame dinero urgente", 11),
    row("Tenemos una relación especial quiero que invirtamos juntos en cripto te garantizo ganancias", 11),
    row("Abuelo soy yo tuve un accidente necesito dinero no le cuentes a mis padres por favor", 11),
    row("Me enamoré de ti en línea y quiero compartir contigo esta oportunidad de inversión única", 11),
    row("Esta plataforma de inversión da beneficios garantizados cada mes ya gané mucho juntos podemos más", 11),
    row("Conocimos en la aplicación de citas hace un mes y siento que eres mi alma gemela invierte conmigo", 11),
    # --- French ---
    row("Grand-mère c'est moi je suis en prison j'ai besoin d'argent pour la caution ne dis rien à maman", 11),
    row("J'ai rencontré quelqu'un en ligne et il m'a présenté une plateforme d'investissement incroyable", 11),
    row("Mon chéri je suis bloqué à l'étranger mon portefeuille a été volé envoie-moi de l'argent", 11),
    row("Notre relation amoureuse est réelle je veux investir avec toi dans la cryptomonnaie", 11),
    row("Grand-père c'est moi j'ai eu un accident j'ai besoin d'argent ne le dis pas à mes parents", 11),
    row("Cette plateforme d'investissement garantit des rendements élevés chaque mois faisons-le ensemble", 11),
    # --- Russian ---
    row("Бабушка это я меня арестовали нужны деньги на залог не говори маме пожалуйста", 11),
    row("Я познакомился онлайн с девушкой она рассказала мне о платформе для инвестиций", 11),
    row("Дедушка я в беде застрял за границей потерял кошелёк пришли денег", 11),
    row("Наши отношения серьёзные я хочу вместе инвестировать в крипто доверяй мне", 11),
    row("Платформа гарантирует доход каждый месяц я уже заработал много давай инвестируем вместе", 11),
    row("Я влюбился в неё онлайн и она показала мне секретную инвестиционную платформу", 11),
    row("Не говори маме бабушка мне нужны деньги срочно я объясню всё потом", 11),
]

# ---------------------------------------------------------------------------
# Label 12 — Delivery / Package scam
# ---------------------------------------------------------------------------
DELIVERY = [
    # --- English ---
    row("Your FedEx package is held at customs please pay a clearance fee of twenty five dollars to release it", 12),
    row("This is DHL notification your shipment cannot be delivered due to unpaid customs duty please pay online", 12),
    row("Your UPS package has been stopped at the border a customs fee of thirty dollars is required to proceed", 12),
    row("USPS alert your parcel is waiting at the post office a small fee is required to reschedule delivery", 12),
    row("Your Amazon package could not be delivered please click the link to pay a redelivery fee of two dollars", 12),
    row("Urgent your international shipment is held at customs and will be returned if fee not paid within 24 hours", 12),
    row("We attempted to deliver your package but nobody was home please pay a storage fee to release it", 12),
    row("Your parcel from China is stuck at customs please provide payment details to clear the import duty", 12),
    row("DHL express your package is pending clearance please visit our website and pay the handling fee now", 12),
    row("This is an automated message from the post office your registered parcel requires a customs payment", 12),
    row("Your tracked shipment has been flagged for inspection please call this number and pay the release fee", 12),
    row("We have a package for you from an overseas sender please pay fifteen dollars customs fee to receive it", 12),
    row("Your package tracking number shows held at customs please click this link immediately to pay and release", 12),
    row("FedEx final notice your parcel will be returned to sender unless customs clearance fee paid by tonight", 12),
    row("Your order from an international seller has been stopped you must pay a tax of forty dollars to receive it", 12),
    row("Notice from Royal Mail your package is held due to unpaid duties please pay online to avoid return", 12),
    row("Your shipment tracking number requires a delivery confirmation fee please follow the link to pay now", 12),
    row("Important DHL message we were unable to deliver your parcel please schedule redelivery and pay the fee", 12),
    row("Your package arrived at the distribution center but customs requires additional payment before delivery", 12),
    row("This is the courier service your package is ready but a small customs surcharge of twelve dollars is due", 12),
    row("Press one to pay your outstanding delivery fee or your package will be sent back to the sender today", 12),
    row("Your package has been in the warehouse for three days a daily storage fee applies please pay now", 12),
    row("We tried to deliver your parcel twice please call us back and provide a credit card to release the package", 12),
    row("Your international package is flagged for random customs inspection a processing fee of twenty dollars applies", 12),
    row("FedEx automated delivery notice your package requires a signature fee please pay via the secure link", 12),
    # --- Hebrew ---
    row("החבילה שלך עוכבה במכס נדרש לשלם אגרת שחרור של חמישים שקל לאישור המשלוח", 12),
    row("הודעה מ-DHL החבילה שלך לא הצלחנו לספק בגלל חוב מכס יש לשלם כדי לשחרר", 12),
    row("פדקס הודעה דחופה החבילה שלך עוכבה נדרש תשלום מכס של שבעים שקל תוך 24 שעות", 12),
    row("הדואר הישראלי מסר לנו שחבילה שלך ממתינה ומחייבת תשלום אגרת מכס לפני שחרור", 12),
    row("המשלוח שלך מסין עוכב בגלור מכס לחץ על הקישור כדי לשלם ולשחרר את החבילה", 12),
    row("UPS הודעה אחרונה חבילתך תוחזר לשולח אם לא תשלם אגרת מכס עד הערב", 12),
    row("ניסינו לספק את החבילה שלך אך לא היית בבית נדרש תשלום לאחסנה כדי לשחרר", 12),
    row("החבילה הבינלאומית שלך עוכבה בנמל תעופה נדרש תשלום מס יבוא של מאה שקל", 12),
    row("הודעה אוטומטית מהדואר החבילה שלך דורשת אימות ותשלום אגרה לפני מסירה", 12),
    row("שלחנו לך חבילה מחו\"ל ועוכבה בגלל בדיקת מכס יש לשלם כדי לקבל אותה", 12),
    # --- Arabic ---
    row("طردك محتجز في الجمارك يرجى دفع رسوم الإفراج البالغة خمسين دولاراً لاستلامه", 12),
    row("إشعار من فيدكس لم نتمكن من تسليم شحنتك بسبب رسوم جمركية غير مدفوعة", 12),
    row("DHL تنبيه عاجل طردك سيعاد للمرسل إذا لم تدفع رسوم الجمارك خلال 24 ساعة", 12),
    row("شحنتك من الخارج محتجزة في المطار يرجى الدفع عبر الإنترنت لإتمام التسليم", 12),
    row("إشعار البريد طردك ينتظر في مركز التوزيع ويحتاج إلى دفع رسوم جمركية", 12),
    row("UPS إشعار أخير طردك سيرتجع إلى المرسل ما لم تدفع رسوم التخليص الجمركي", 12),
    row("حاولنا توصيل طردك مرتين ولم نجد أحداً يرجى الدفع لإعادة الجدولة", 12),
    row("طردك الدولي خضع للفحص العشوائي وتستحق رسوم معالجة قدرها عشرين دولاراً", 12),
    # --- Spanish ---
    row("Tu paquete de FedEx está retenido en aduana paga una tarifa de treinta dólares para liberarlo", 12),
    row("Aviso de DHL tu envío no pudo ser entregado por aranceles aduaneros impagos paga ahora", 12),
    row("UPS notificación urgente tu paquete será devuelto si no pagas la tarifa aduanera hoy", 12),
    row("Tu pedido internacional está retenido en la aduana debes pagar veinte dólares para recibirlo", 12),
    row("Correos España tu paquete requiere pago de tasas de aduana antes de ser entregado", 12),
    row("Entrega fallida tu paquete espera en la oficina de correos paga la tarifa de almacenamiento", 12),
    row("Tu envío de China está bloqueado en aduana haz clic en el enlace para pagar y liberar el paquete", 12),
    row("Aviso final de mensajería tu paquete será devuelto al remitente esta noche si no pagas", 12),
    # --- French ---
    row("Votre colis FedEx est retenu en douane veuillez payer des frais de dédouanement de vingt euros", 12),
    row("Avis DHL votre envoi n'a pas pu être livré en raison de droits de douane impayés payez maintenant", 12),
    row("La Poste votre colis est en attente de dédouanement veuillez payer les frais avant livraison", 12),
    row("Votre colis international est bloqué à la douane cliquez sur le lien pour payer et libérer", 12),
    row("Avis final votre colis sera retourné à l'expéditeur si les frais de douane ne sont pas payés ce soir", 12),
    row("Impossible de livrer votre colis veuillez payer les frais de stockage pour le récupérer", 12),
    # --- Russian ---
    row("Ваша посылка задержана на таможне оплатите таможенный сбор в размере тысячи рублей для получения", 12),
    row("Уведомление FedEx ваша посылка не может быть доставлена из-за неоплаченной пошлины", 12),
    row("DHL ваша посылка ожидает на таможне оплатите пошлину в течение 24 часов иначе посылка вернётся", 12),
    row("Почта России ваша посылка задержана на таможне перейдите по ссылке чтобы оплатить и получить", 12),
    row("Ваш международный пакет заблокирован на таможне требуется оплата в размере пятисот рублей", 12),
    row("Срочно ваша посылка будет возвращена отправителю если вы не оплатите таможню сегодня", 12),
]

# ---------------------------------------------------------------------------
# Label 13 — Job / Recruitment scam
# ---------------------------------------------------------------------------
JOB = [
    # --- English ---
    row("Congratulations your resume was selected for a work from home position earning five hundred per week", 13),
    row("We are hiring remote workers no experience needed you can earn up to two thousand dollars per month", 13),
    row("This is a job offer for an online data entry position you need to pay a training fee of one hundred dollars", 13),
    row("We found your CV online and we have an exciting part time job opportunity that pays very well", 13),
    row("You have been selected for a remote customer service position start immediately earn eight hundred per week", 13),
    row("Hello this is Amazon recruitment we have a work from home job opening for you apply now", 13),
    row("We are looking for package forwarding agents work from home earn fifty dollars per package processed", 13),
    row("You qualify for our reshipping agent position receive packages at home and forward them for payment", 13),
    row("This is a money transfer agent position you will receive payments and forward them keeping a commission", 13),
    row("We need mystery shoppers work from home no experience needed we will send you a check to start", 13),
    row("Your application has been approved please provide your bank account details so we can set up your payroll", 13),
    row("To activate your remote job position please pay a refundable registration fee of two hundred dollars", 13),
    row("We have a flexible part time data entry job for you earn money from home in your spare time apply now", 13),
    row("Google is hiring remote workers earn fifteen hundred a week applying takes two minutes no experience", 13),
    row("Hi I am a recruiter we are looking for online assistants you can work from home and earn well", 13),
    row("We will send you a check for your first week's salary just cash it and return the portion for equipment", 13),
    row("Please provide your social security number and bank routing number so HR can set up your direct deposit", 13),
    row("You have been selected for a secret shopper position we are sending you a cashier's check to purchase items", 13),
    row("Our company needs remote package coordinators you receive packages verify contents and reship them", 13),
    row("This exclusive work from home opportunity pays twelve hundred weekly you just need to pay for the starter kit", 13),
    row("We are a staffing agency your profile matched our client's need please pay for the background check to proceed", 13),
    row("Earn money from home by forwarding packages no experience required we cover all shipping costs", 13),
    row("You are pre-approved for our online job your first task starts today just provide your bank account number", 13),
    row("We are offering a remote bookkeeping job pay two hundred training fee get three thousand monthly salary", 13),
    row("Hello this is a recruiter from LinkedIn your profile is impressive we have a high paying remote role for you", 13),
    row("Work from home twenty hours a week earn one thousand per week no experience needed start this week", 13),
    row("To complete your onboarding please purchase fifty dollars of gift cards and send the codes to your manager", 13),
    row("Your job offer is confirmed please send us your full name address and bank account for payroll setup", 13),
    row("We are hiring social media evaluators work from home part time two hundred dollars per day guaranteed", 13),
    row("Easy data entry job online earn five dollars per form no experience work your own hours from home", 13),
    # --- Hebrew ---
    row("זכית בהצעת עבודה מהבית בשכר של אלף שקל לשבוע ללא ניסיון נדרש הצטרף עכשיו", 13),
    row("מצאנו את קורות החיים שלך ויש לנו תפקיד אטרקטיבי של עבודה מהבית עבורך", 13),
    row("אנחנו מגייסים סוכנים לעבודה גמישה מהבית תרוויח אלף וחמש מאות שקל בחודש", 13),
    row("הצעת עבודה מיוחדת עבודת הזנת נתונים מהבית ללא ניסיון שלח פרטי חשבון בנק", 13),
    row("לצורך קבלת תפקיד מרחוק יש לשלם דמי הכשרה של מאה שקל שיוחזרו בחודש הראשון", 13),
    row("חברת גיוס מצאה לך תפקיד כמנהל חבילות מהבית שלח את פרטי חשבון הבנק לחוזה", 13),
    row("עבודה קלה מהבית הרוויח מאתיים שקל לשעה ללא ניסיון עם שעות גמישות", 13),
    row("אנחנו מחפשים נציגי משלוחים לעבודה מהבית קבל חבילות ושלח אותן הלאה בתשלום", 13),
    row("גוגל מגייסת עובדים מרחוק ישראל שלם כלים למסורת עבודה וקבל אלפיים שקל בחודש", 13),
    row("תפקיד ניהול נתונים מהבית מתאים לסטודנטים ולעצמאים שלח פרטי חשבון לתחילת עבודה", 13),
    # --- Arabic ---
    row("تهانينا تم اختيارك لوظيفة عمل من المنزل براتب ألف دولار في الشهر بدون خبرة", 13),
    row("وجدنا سيرتك الذاتية لدينا فرصة عمل ممتازة من المنزل برواتب مجزية", 13),
    row("نحن نوظف وكلاء شحن للعمل من المنزل استقبل طرودا وأعد شحنها مقابل عمولة", 13),
    row("لإتمام التسجيل في الوظيفة يرجى دفع رسوم التدريب بمبلغ مائة دولار قابلة للاسترداد", 13),
    row("وظيفة مرنة عمل جزئي من المنزل بدون خبرة أرسل رقم حسابك البنكي للبدء", 13),
    row("شركة التوظيف تبحث عن مقيمين اجتماعيين عبر الإنترنت اكسب مائتي دولار يومياً", 13),
    row("تم قبول طلبك للوظيفة يرجى إرسال بيانات حسابك البنكي لإعداد الرواتب", 13),
    row("عمل سهل من المنزل إدخال بيانات اكسب خمسة دولارات لكل نموذج بدون خبرة", 13),
    # --- Spanish ---
    row("Felicidades tu currículum fue seleccionado para un trabajo desde casa que paga quinientos dólares semanales", 13),
    row("Estamos contratando agentes remotos sin experiencia previa gana hasta dos mil dólares mensuales", 13),
    row("Esta es una oferta de trabajo de entrada de datos en línea se requiere pagar una tarifa de formación", 13),
    row("Para activar tu posición remota paga una cuota de registro reembolsable de doscientos dólares", 13),
    row("Somos una agencia de empleo necesitamos agentes de reenvío de paquetes trabaja desde casa", 13),
    row("Google está contratando trabajadores remotos gana mil quinientos por semana sin experiencia", 13),
    row("Trabajo flexible desde casa gana dinero en tu tiempo libre envíanos tu número de cuenta bancaria", 13),
    row("Hemos encontrado tu perfil y tenemos un puesto de trabajo remoto muy bien remunerado para ti", 13),
    # --- French ---
    row("Félicitations votre CV a été sélectionné pour un poste à domicile rémunéré cinq cents euros par semaine", 13),
    row("Nous recrutons des agents à distance sans expérience requise gagnez jusqu'à deux mille euros par mois", 13),
    row("Pour activer votre poste à domicile veuillez payer des frais de formation remboursables de cent euros", 13),
    row("Nous cherchons des agents de réexpédition de colis travail à domicile gagnez cinquante euros par colis", 13),
    row("Google recrute des travailleurs à distance France gagnez mille cinq cents par semaine sans expérience", 13),
    row("Cette offre d'emploi à mi-temps depuis chez vous paie deux cents euros par jour sans expérience", 13),
    # --- Russian ---
    row("Поздравляем ваше резюме выбрано для удалённой работы зарплата пятьдесят тысяч рублей в месяц", 13),
    row("Мы нанимаем удалённых сотрудников без опыта работа из дома заработок до ста тысяч в месяц", 13),
    row("Для активации удалённой позиции оплатите обучающий взнос в размере двух тысяч рублей", 13),
    row("Нам нужны агенты по пересылке посылок работа из дома комиссионное вознаграждение", 13),
    row("Google нанимает удалённых сотрудников Россия отправьте реквизиты счёта для оформления", 13),
    row("Лёгкий заработок из дома ввод данных без опыта пятьсот рублей за форму", 13),
]

# ---------------------------------------------------------------------------
# Expansions of existing labels with newer patterns
# ---------------------------------------------------------------------------
EXPANSIONS = [
    # Label 1 — Utility shutoff & government benefit fraud
    row("This is an urgent notice from your electric company your power will be disconnected in two hours unless you pay", 1),
    row("Your electricity service will be shut off today unless you call us and make a payment immediately", 1),
    row("This is your gas company you owe a past due balance your service will be terminated in 30 minutes", 1),
    row("You qualify for a COVID relief payment of twelve hundred dollars from the federal government call now to claim", 1),
    row("The government has approved your stimulus check of fourteen hundred dollars call to verify your details", 1),
    row("You are eligible for student loan forgiveness act now to have your balance wiped call this number immediately", 1),
    row("FEMA disaster relief funds are available for your area call now to claim your benefit before it expires", 1),
    row("חברת החשמל הודעה דחופה החשמל שלך יינתק בשעתיים אם לא תשלם עכשיו", 1),
    row("הממשלה אישרה קצבת סיוע עבורך התקשר עכשיו לאימות הפרטים לפני שהזמן יפוג", 1),
    row("شركة الكهرباء ستنقطع الخدمة عنك خلال ساعتين ما لم تدفع الآن اتصل فوراً", 1),
    row("Se le cortará el servicio eléctrico hoy a menos que llame y pague su saldo pendiente ahora", 1),
    row("Votre électricité sera coupée dans deux heures si vous ne payez pas votre solde impayé maintenant", 1),

    # Label 2 — AI chatbot / unusual login tech threats
    row("We detected unusual login activity on your Microsoft account from an unknown device call tech support now", 2),
    row("Your Apple ID was accessed from Russia you must call us immediately to secure your account", 2),
    row("Our AI security system detected suspicious activity on your computer a technician will assist you now", 2),
    row("Your Google account was compromised call our security team immediately to restore access", 2),
    row("This is an automated security alert your device was hacked call our technicians right now to fix it", 2),
    row("זיהינו כניסה חשודה לחשבון המיקרוסופט שלך מכתובת IP לא מוכרת התקשר לתמיכה עכשיו", 2),
    row("تم الوصول إلى حسابك من موقع غير معروف اتصل بدعم مايكروسوفت الآن لتأمين حسابك", 2),

    # Label 3 — AI voice cloning bank fraud
    row("Hi this is your bank we detected a suspicious transfer of five thousand dollars please verify now", 3),
    row("Your bank account shows an unauthorized wire transfer please call us immediately to reverse it", 3),
    row("We are calling from your bank fraud department a large purchase was made on your card call back now", 3),
    row("This is an automated alert from Chase Bank a wire transfer of three thousand dollars is pending verify now", 3),
    row("הבנק שלך מתקשר הבחנו בפעילות חשודה בחשבון שלך ויש לאמת את זהותך עכשיו", 3),
    row("اتصلنا من قسم الاحتيال في بنكك تم رصد تحويل مشبوه يرجى التحقق من هويتك الآن", 3),

    # Label 7 — SIM swap social engineering
    row("This is your mobile carrier we need to verify your account before processing your SIM transfer request", 7),
    row("Someone has requested a SIM swap on your account please confirm your PIN to cancel this request", 7),
    row("Your phone number is being transferred to a new SIM card call us now if you did not request this", 7),
    row("ספק הסלולר שלך מתקשר מישהו ביקש ל-swap את ה-SIM שלך אמת את זהותך עכשיו", 7),
    row("شركة الهاتف تتصل بك طلب شخص ما تبديل شريحة SIM الخاصة بك أكد هويتك الآن", 7),

    # Label 9 — Crypto recovery & NFT scam
    row("We can recover your lost Bitcoin our blockchain experts have already traced your stolen funds call now", 9),
    row("You lost money in a crypto scam we are a recovery firm with a ninety percent success rate pay us to start", 9),
    row("Our NFT platform is launching exclusive tokens with guaranteed returns invest before the public sale", 9),
    row("Your NFT collection has been compromised click the link immediately to secure your wallet", 9),
    row("We detected that your crypto wallet was hacked our recovery service can restore your funds for a fee", 9),
    row("Exclusive NFT drop only five hundred spots available guaranteed ten times return in thirty days invest now", 9),
    row("יכולים לשחזר את הביטקוין האבוד שלך שלם דמי שחזור ותחזיר את כל הכסף שלך", 9),
    row("يمكننا استرداد العملات المشفرة المسروقة ادفع رسوم الاسترداد وسنبدأ الآن", 9),
]

# ---------------------------------------------------------------------------
# Legitimate calls that should NOT be confused with new scam types
# (extra negatives help the model avoid false positives)
# ---------------------------------------------------------------------------
LEGIT_EXTRA = [
    row("Hi this is Sarah from FedEx your package is on the way and will arrive tomorrow no action needed", 0),
    row("This is DHL your delivery has been successfully completed have a great day", 0),
    row("Your Amazon order has shipped and will arrive by Friday no payment needed", 0),
    row("This is a courtesy call from the post office your package is ready for pickup at the branch", 0),
    row("Hi I am calling from the staffing agency about your job application we would like to schedule an interview", 0),
    row("This is LinkedIn recruiter I found your profile and we have a legitimate position I'd like to discuss", 0),
    row("Hello I'm calling about your online dating profile I'd love to meet for coffee this weekend", 0),
    row("This is grandma calling just wanted to check in on you let me know if you need anything sweetheart", 0),
    row("I met someone at the gym and we've been dating for a month she is lovely and we share many interests", 0),
    row("Your electricity bill is due next week you can pay online or by phone no disconnection threat at this time", 0),
    row("The government has processed your tax return and your refund will arrive within ten business days", 0),
    row("This is your bank you received a direct deposit today your balance has been updated have a good day", 0),
    row("Hello I'm a recruiter from a real company we have a salaried position with benefits I'd like to discuss", 0),
    row("Your package was delivered to your front door today no signature was required have a good evening", 0),
    row("Hi this is Microsoft support following up on your previously logged support ticket number", 0),
]


def main():
    all_rows = ROMANCE + DELIVERY + JOB + EXPANSIONS + LEGIT_EXTRA
    print(f"Adding {len(all_rows)} new rows to {CSV_PATH}")
    print(f"  Label 11 (Romance/Pig-Butchering): {sum(1 for r in all_rows if r['label'] == 11)}")
    print(f"  Label 12 (Delivery/Package):       {sum(1 for r in all_rows if r['label'] == 12)}")
    print(f"  Label 13 (Job/Recruitment):        {sum(1 for r in all_rows if r['label'] == 13)}")
    print(f"  Expansions (existing labels):      {len(EXPANSIONS)}")
    print(f"  Extra legit (label 0):             {len(LEGIT_EXTRA)}")

    with open(CSV_PATH, "a", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=FIELDNAMES)
        writer.writerows(all_rows)

    print("Done.")


if __name__ == "__main__":
    main()