#!/usr/bin/env python3
"""
Add Spanish and French labelled training examples to training_data.csv.

Also adds extra Tech Support (label 2) and Legitimate (label 0) examples
to improve the weakest classes (84.88% and 90.48% accuracy respectively).

Labels:
0  Legitimate
1  IRS scam
2  Tech support scam
3  Bank fraud
4  Lottery / prize scam
5  Social Security scam
6  Robocall
7  Phishing
8  Insurance scam
9  Investment scam
10 Donation fraud
"""

import csv
import os
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
CSV_PATH = os.path.join(SCRIPT_DIR, "training_data.csv")

ROWS = [

    # ==================================================================
    # SPANISH examples  (label, text)
    # ==================================================================

    # --- Legitimate (0) ---
    (0, "Hola, le llamo para confirmar su cita del martes a las tres de la tarde."),
    (0, "Buenos días, soy de la farmacia y su pedido está listo para recoger."),
    (0, "Le llamo de la consulta del doctor Ramírez para recordarle su revisión anual."),
    (0, "Soy su agente de seguros, le llamo para renovar su póliza que vence el mes que viene."),
    (0, "Le informamos que su paquete ha sido entregado en la dirección indicada."),
    (0, "Buenas tardes, le llamo del banco para confirmar que recibió su nueva tarjeta."),
    (0, "Hola, soy su vecino, solo quería avisarle que su coche está bloqueando la entrada."),

    # --- IRS / Tax (1) ---
    (1, "Le llama la Agencia Tributaria. Tiene una deuda fiscal pendiente y debe pagar inmediatamente o enfrentará una orden de arresto."),
    (1, "Este es un mensaje urgente de hacienda. Su declaración de impuestos muestra irregularidades. Llámenos ahora para evitar consecuencias legales."),
    (1, "Tiene una deuda fiscal de 3.500 euros con el servicio de impuestos. Si no paga hoy emitiremos una orden de arresto."),
    (1, "El servicio de impuestos ha detectado fraude fiscal en su nombre. Actúe ahora mismo o será detenido."),
    (1, "Soy inspector de hacienda. Debe impuestos atrasados. Pague con tarjeta de regalo o le arrestarán hoy."),
    (1, "La agencia tributaria ha iniciado un proceso de embargo. Llame ahora para resolver su deuda fiscal urgente."),
    (1, "Su número fiscal ha sido comprometido. Contacte con el servicio de impuestos inmediatamente para evitar el arresto."),

    # --- Tech Support (2) ---
    (2, "Soporte técnico de Microsoft. Hemos detectado un virus informático en su ordenador. Necesitamos acceso remoto para solucionarlo."),
    (2, "Su computadora está infectada con software malicioso. Instale anydesk ahora mismo para que nuestros técnicos puedan limpiarla."),
    (2, "Le llama asistencia técnica de Windows. Su sistema ha sido hackeado. Debe darnos acceso remoto inmediatamente."),
    (2, "Soporte de Apple. Hemos detectado actividad sospechosa en su iPhone. Instale teamviewer para que podamos proteger su dispositivo."),
    (2, "Su computadora tiene un virus crítico que está robando su información personal. Llame a nuestros técnicos ahora mismo."),
    (2, "Este es un aviso de seguridad de Windows. Su licencia ha expirado y su ordenador está en riesgo. Llame a soporte técnico urgente."),
    (2, "Hemos detectado que alguien está intentando acceder a su computadora de forma remota. Necesitamos conectarnos para protegerla."),
    (2, "Soporte técnico urgente: su router está comprometido y todos sus dispositivos están infectados con software malicioso."),

    # --- Bank Fraud (3) ---
    (3, "Su cuenta bancaria ha sido bloqueada por actividad sospechosa. Llame ahora para verificar su identidad y recuperar el acceso."),
    (3, "El banco ha detectado una transacción no autorizada en su tarjeta de crédito. Confirme sus datos inmediatamente."),
    (3, "Fraude bancario detectado. Su cuenta está congelada. Necesitamos verificar su número de cuenta y contraseña para reactivarla."),
    (3, "Su tarjeta de crédito ha sido bloqueada. Llame a su banco ahora mismo para confirmar su identidad y desbloquearla."),
    (3, "Hemos detectado acceso no autorizado a su cuenta bancaria desde un dispositivo desconocido. Verifique sus datos de inmediato."),
    (3, "Aviso de seguridad del banco: su cuenta ha sido suspendida. Proporcione su número de cuenta para reactivarla."),

    # --- Lottery / Prize (4) ---
    (4, "Felicitaciones, ganaste el premio mayor de nuestra lotería internacional. Llame ahora para reclamar su premio en efectivo."),
    (4, "Ha sido seleccionado como ganador de nuestro sorteo. Para recibir su premio de 50.000 euros, pague primero los gastos de gestión."),
    (4, "Enhorabuena. Su número ha resultado premiado en nuestra lotería exclusiva. Reclamar su premio ahora antes de que expire."),
    (4, "Usted es el ganador de un viaje gratis a las Maldivas. Solo tiene que pagar las tasas de envío para recibir su premio."),
    (4, "Hemos seleccionado su número de teléfono como ganador de 100.000 dólares. Llame ahora para reclamar su premio."),

    # --- Social Security (5) ---
    (5, "Su número de seguridad social ha sido comprometido en actividades ilegales. Contacte con nosotros inmediatamente o será arrestado."),
    (5, "La seguridad social ha suspendido su DNI por actividad sospechosa. Llame ahora para evitar consecuencias legales."),
    (5, "Su número de seguridad social está vinculado a un caso de tráfico de drogas. Llame de inmediato a la policía federal."),
    (5, "Hemos detectado actividad ilegal asociada a su número de seguridad social. Debe verificar sus documentos de identidad urgentemente."),
    (5, "El departamento de seguridad social va a suspender sus beneficios. Llame ahora para confirmar su información personal."),

    # --- Robocall (6) ---
    (6, "Este es un mensaje grabado. Su garantía extendida del vehículo está a punto de expirar. Presione 1 para hablar con un agente."),
    (6, "No cuelgue. Este es un aviso final sobre su cuenta. Presione 1 ahora para hablar con un representante inmediatamente."),
    (6, "Mensaje grabado urgente. Ha ganado un crucero gratis. Presione 1 para reclamar su premio antes de que expire esta oferta."),
    (6, "Este es su último aviso. Su servicio será cancelado hoy si no llama de vuelta al número indicado inmediatamente."),
    (6, "Mensaje automático: su pago ha sido rechazado. Presione 1 ahora o su cuenta será suspendida permanentemente."),

    # --- Phishing (7) ---
    (7, "Su cuenta ha sido bloqueada. Haga clic en el enlace para verificar su identidad y actualizar su información personal."),
    (7, "Actualice su contraseña ahora mismo. Su cuenta ha sido comprometida. Haga clic aquí para restablecer sus credenciales de acceso."),
    (7, "Aviso de seguridad: alguien intentó acceder a su cuenta. Confirme sus datos de usuario haciendo clic en el enlace adjunto."),
    (7, "Su sesión ha expirado. Ingrese su contraseña y número de cuenta en el enlace de abajo para reactivar su acceso."),
    (7, "Le informamos que debe verificar su información de facturación. Haga clic en el enlace y actualice sus datos inmediatamente."),

    # --- Insurance (8) ---
    (8, "Usted podría calificar para un seguro de salud gratuito del gobierno. Llame ahora para saber si es elegible para cobertura médica."),
    (8, "Le ofrecemos una póliza de seguro médico a precio reducido. Califica por su edad. Llame para activar su cobertura médica hoy."),
    (8, "Tiene derecho a un seguro de salud gratuito. Llame ahora antes de que expire esta oferta por tiempo limitado."),
    (8, "Hemos revisado su perfil y usted es elegible para una póliza de seguro médico sin costo. Actúe ahora, oferta limitada."),

    # --- Investment Scam (9) ---
    (9, "Inversión garantizada con 40% de rendimiento mensual. Bitcoin y criptomoneda. Únase a miles de inversores con ingresos pasivos."),
    (9, "Oportunidad exclusiva de trading. Doble su dinero en 30 días con nuestra plataforma de criptomoneda garantizada."),
    (9, "Le ofrecemos una inversión en bitcoin con ganancias garantizadas. Libertad financiera al alcance de su mano. Llame ahora."),
    (9, "Nuestros expertos en forex y criptomoneda le enseñarán a generar ingresos pasivos. Rentabilidad garantizada del 200%."),
    (9, "Inversión sin riesgo en bitcoin. Hemos ayudado a miles de clientes a duplicar su dinero con nuestra estrategia exclusiva."),

    # --- Donation Fraud (10) ---
    (10, "Por favor done para ayudar a las víctimas del terremoto. Su donación va directamente a las familias damnificadas, sin intermediarios."),
    (10, "Recaudación de fondos urgente para niños en zonas de guerra. Done ahora y salve una vida. Sin fines de lucro certificado."),
    (10, "Ayuda humanitaria urgente. Done hoy para las víctimas del huracán. Su contribución deducible de impuestos ayuda a familias necesitadas."),
    (10, "Nuestra organización sin fines de lucro necesita su donación para ayudar a refugiados. Cada euro cuenta. Done ahora."),
    (10, "Caridad internacional. Estamos recaudando fondos de emergencia para víctimas de inundaciones. Por favor contribuya hoy."),

    # ==================================================================
    # FRENCH examples
    # ==================================================================

    # --- Legitimate (0) ---
    (0, "Bonjour, je vous appelle pour confirmer votre rendez-vous de mardi à quinze heures."),
    (0, "Bonjour, c'est la pharmacie. Votre ordonnance est prête à être récupérée."),
    (0, "Je vous contacte du cabinet du docteur Dupont pour vous rappeler votre bilan annuel."),
    (0, "Bonjour, votre colis a bien été livré à l'adresse indiquée ce matin."),
    (0, "C'est votre banque. Nous vous appelons pour confirmer que vous avez bien reçu votre nouvelle carte."),
    (0, "Bonjour, je suis votre voisin. Je voulais juste vous prévenir que votre voiture bloque l'entrée."),
    (0, "Je vous appelle de la mairie pour vous informer que votre dossier a bien été reçu et est en cours de traitement."),

    # --- IRS / Tax (1) ---
    (1, "Vous avez une dette fiscale en souffrance. Payez immédiatement au service des impôts ou une procédure d'arrestation sera engagée."),
    (1, "Message urgent des impôts. Des irrégularités ont été détectées dans votre déclaration. Appelez maintenant pour éviter des poursuites judiciaires."),
    (1, "Vous devez 4 200 euros au fisc. Si vous ne payez pas aujourd'hui, la police sera envoyée à votre domicile."),
    (1, "La direction générale des finances publiques a détecté une fraude fiscale à votre nom. Agissez maintenant."),
    (1, "Je suis inspecteur des impôts. Vous avez des arriérés fiscaux. Payez par carte cadeau ou vous serez arrêté aujourd'hui."),
    (1, "Les impôts ont initié une procédure de saisie. Appelez immédiatement pour régulariser votre dette fiscale urgente."),

    # --- Tech Support (2) ---
    (2, "Support technique de Microsoft. Nous avons détecté un virus informatique sur votre ordinateur. Nous avons besoin d'un accès à distance pour le résoudre."),
    (2, "Votre ordinateur est infecté par un logiciel malveillant. Installez anydesk maintenant pour que nos techniciens puissent le nettoyer."),
    (2, "Assistance technique Windows. Votre système a été piraté. Vous devez nous donner un accès à distance immédiatement."),
    (2, "Support Apple. Nous avons détecté une activité suspecte sur votre iPhone. Installez teamviewer pour que nous puissions sécuriser votre appareil."),
    (2, "Votre ordinateur a un virus critique qui vole vos informations personnelles. Appelez nos techniciens maintenant."),
    (2, "Avertissement de sécurité Windows. Votre licence a expiré et votre ordinateur est en danger. Appelez le support technique urgent."),
    (2, "Nous avons détecté que quelqu'un tente d'accéder à votre ordinateur à distance. Nous devons nous connecter pour le protéger."),
    (2, "Support technique urgent : votre routeur est compromis et tous vos appareils sont infectés par des logiciels malveillants."),

    # --- Bank Fraud (3) ---
    (3, "Votre compte bancaire a été bloqué suite à une activité suspecte. Appelez maintenant pour vérifier votre identité."),
    (3, "La banque a détecté une transaction non autorisée sur votre carte de crédit. Confirmez vos coordonnées immédiatement."),
    (3, "Fraude bancaire détectée. Votre compte est gelé. Nous avons besoin de votre numéro de compte pour le réactiver."),
    (3, "Votre carte de crédit a été bloquée. Appelez votre banque maintenant pour confirmer votre identité et la débloquer."),
    (3, "Nous avons détecté un accès non autorisé à votre compte bancaire depuis un appareil inconnu. Vérifiez vos données immédiatement."),
    (3, "Alerte sécurité de la banque : votre compte a été suspendu. Fournissez votre numéro de compte pour le réactiver."),

    # --- Lottery / Prize (4) ---
    (4, "Félicitations, vous avez gagné le gros lot de notre loterie internationale. Appelez maintenant pour réclamer votre prix en espèces."),
    (4, "Vous avez été sélectionné comme gagnant de notre tirage au sort. Pour recevoir votre prix de 50 000 euros, payez d'abord les frais de gestion."),
    (4, "Toutes nos félicitations. Votre numéro a été tiré lors de notre loterie exclusive. Réclamez votre prix avant qu'il n'expire."),
    (4, "Vous avez gagné un voyage gratuit aux Maldives. Il vous suffit de payer les frais d'envoi pour recevoir votre prix."),
    (4, "Nous avons sélectionné votre numéro de téléphone comme gagnant de 100 000 dollars. Appelez maintenant pour réclamer."),

    # --- Social Security (5) ---
    (5, "Votre numéro de sécurité sociale a été compromis dans des activités illégales. Contactez-nous immédiatement ou vous serez arrêté."),
    (5, "La sécurité sociale a suspendu votre carte d'identité pour activité suspecte. Appelez maintenant pour éviter des poursuites judiciaires."),
    (5, "Votre numéro de sécurité sociale est lié à une affaire de trafic de drogue. Appelez immédiatement la police fédérale."),
    (5, "Nous avons détecté une activité illégale associée à votre numéro de sécurité sociale. Vérifiez vos pièces d'identité d'urgence."),
    (5, "Le département de sécurité sociale va suspendre vos prestations. Appelez maintenant pour confirmer vos informations personnelles."),

    # --- Robocall (6) ---
    (6, "Ceci est un message enregistré. Votre garantie prolongée de véhicule est sur le point d'expirer. Appuyez sur 1 pour parler à un agent."),
    (6, "Ne raccrochez pas. Ceci est un avis final concernant votre compte. Appuyez sur 1 maintenant pour parler à un représentant immédiatement."),
    (6, "Message enregistré urgent. Vous avez gagné une croisière gratuite. Appuyez sur 1 pour réclamer votre prix avant l'expiration de cette offre."),
    (6, "Ceci est votre dernier avis. Votre service sera annulé aujourd'hui si vous ne rappeler pas le numéro indiqué immédiatement."),
    (6, "Message automatique : votre paiement a été rejeté. Appuyez sur 1 maintenant ou votre compte sera suspendu définitivement."),

    # --- Phishing (7) ---
    (7, "Votre compte a été bloqué. Cliquez sur le lien pour vérifier votre identité et mettre à jour vos informations personnelles."),
    (7, "Mettez à jour votre mot de passe maintenant. Votre compte a été compromis. Cliquez ici pour réinitialiser vos identifiants."),
    (7, "Alerte sécurité : quelqu'un a tenté d'accéder à votre compte. Confirmez vos données en cliquant sur le lien ci-joint."),
    (7, "Votre session a expiré. Entrez votre mot de passe et numéro de compte sur le lien ci-dessous pour réactiver votre accès."),
    (7, "Veuillez vérifier vos informations de facturation. Cliquez sur le lien et mettez à jour vos données immédiatement."),

    # --- Insurance (8) ---
    (8, "Vous pourriez bénéficier d'une assurance maladie gratuite du gouvernement. Appelez maintenant pour savoir si vous êtes éligible à une couverture médicale."),
    (8, "Nous vous proposons une mutuelle à prix réduit. Vous êtes éligible en raison de votre âge. Appelez pour activer votre couverture médicale aujourd'hui."),
    (8, "Vous avez droit à une assurance santé gratuite. Appelez maintenant avant l'expiration de cette offre limitée dans le temps."),
    (8, "Nous avons examiné votre profil et vous êtes éligible à une assurance maladie sans frais. Agissez maintenant, offre limitée."),

    # --- Investment Scam (9) ---
    (9, "Investissement garanti avec 40% de rendement mensuel. Bitcoin et cryptomonnaie. Rejoignez des milliers d'investisseurs avec des revenus passifs."),
    (9, "Opportunité exclusive de trading. Doublez votre argent en 30 jours avec notre plateforme de cryptomonnaie garantie."),
    (9, "Nous vous proposons un investissement en bitcoin avec des gains garantis. La liberté financière à portée de main. Appelez maintenant."),
    (9, "Nos experts en forex et cryptomonnaie vous apprendront à générer des revenus passifs. Rendement garanti de 200%."),
    (9, "Investissement sans risque en bitcoin. Nous avons aidé des milliers de clients à doubler leur argent avec notre stratégie exclusive."),

    # --- Donation Fraud (10) ---
    (10, "Veuillez faire un don pour aider les victimes du tremblement de terre. Votre don va directement aux familles sinistrées."),
    (10, "Collecte de fonds urgente pour les enfants en zones de guerre. Faites un don maintenant et sauvez une vie. Organisation à but non lucratif."),
    (10, "Aide humanitaire urgente. Faites un don aujourd'hui pour les victimes de l'ouragan. Votre contribution est déductible des impôts."),
    (10, "Notre organisation à but non lucratif a besoin de votre don pour aider les réfugiés. Chaque euro compte. Faites un don maintenant."),
    (10, "Charité internationale. Nous collectons des fonds d'urgence pour les victimes des inondations. Veuillez contribuer aujourd'hui."),

    # ==================================================================
    # EXTRA Tech Support examples (label 2) — improve 84.88% accuracy
    # ==================================================================
    (2, "This is Microsoft technical support. Your computer has been sending us error reports. We need you to install our remote access tool immediately."),
    (2, "Hello, I am calling from Windows security department. We have detected unauthorized access attempts on your PC. Please do not turn it off."),
    (2, "Your computer license has been hacked. Call our toll-free number now so our certified technicians can fix it remotely using teamviewer."),
    (2, "We are calling from Apple support. Your iCloud account has been compromised. We need you to install our security software right away."),
    (2, "This is an automated alert from your internet provider. A virus has infected your router. Call our support line immediately to fix it."),
    (2, "Your antivirus subscription has expired and malware has been detected. Call us now and we will remotely remove the infection for free."),
    (2, "Hello, this is Google security team. Your Gmail account has been hacked. We need remote access to your device to recover it."),
    (2, "We detected ransomware on your system. Do not restart your computer. Call our tech support hotline now or you will lose all your files."),
    (2, "Your device is sending out spam emails due to a virus. Connect us via anydesk so our technicians can clean your system right now."),
    (2, "This is Dell computer support. Our servers detected critical errors on your hard drive. Allow remote access now to prevent data loss."),
    (2, "FBI cyber division: your computer IP address has been flagged for illegal activity. Call tech support immediately to clear your system."),
    (2, "Warning: your Windows firewall has been disabled by hackers. Our technicians are standing by to restore your security. Call now."),
    (2, "Your computer has been blocked by Microsoft. Call this number immediately to unlock it. Do not attempt to restart."),
    (2, "McAfee security alert: your subscription has expired and five viruses have been detected. Call us now for free removal assistance."),
    (2, "Hello I'm calling from Norton antivirus. Your license expired yesterday and your computer is now vulnerable. Let us help you remotely."),

    # ==================================================================
    # EXTRA Legitimate examples (label 0) — reduce 9.52% false-positive rate
    # ==================================================================
    (0, "Hi, this is your dentist office calling to confirm your cleaning appointment next Thursday at two pm. Please call us if you need to reschedule."),
    (0, "Hello, I'm calling from the library. The book you reserved is now available for pickup. You have seven days to collect it."),
    (0, "This is a reminder from your gym membership. Your annual renewal is coming up next month. No action needed if you want to continue."),
    (0, "Hi, calling from the vet clinic. Fluffy's annual shots are due next week. Give us a call to book a convenient time."),
    (0, "Hello, this is your property manager. We will be doing routine fire alarm testing in your building next Tuesday morning."),
    (0, "Hi there, calling from the school regarding your child's upcoming field trip next Friday. Please return the signed permission slip."),
    (0, "This is a courtesy call from your eye doctor. Your glasses are ready and available for pickup at our downtown location."),
    (0, "Hello, I'm calling from the city water department. We will be doing scheduled maintenance in your area tomorrow between nine and noon."),
    (0, "Hi, your car is ready at the mechanic shop. The oil change and tire rotation are complete. Total comes to eighty-five dollars."),
    (0, "This is your cable provider. We are letting you know about planned network maintenance in your area from midnight to four am tonight."),
    (0, "Hello, calling from HR regarding your open enrollment period for benefits. Please log into the employee portal to review your options."),
    (0, "Hi, this is the hotel confirming your reservation for next weekend. Check-in is at three pm and check-out is at noon on Sunday."),
    (0, "Hello, I am your financial advisor. I am calling to review your investment portfolio performance for this quarter. When is a good time to meet?"),
    (0, "This is the food bank volunteer coordinator. Thank you for signing up to volunteer on Saturday. Please arrive at nine in the morning."),
    (0, "Hello, calling from the real estate office. The seller has accepted your offer on the property. Congratulations! Please call back to discuss next steps."),
    (0, "Hi, this is your internet service provider. We noticed a brief outage in your area earlier today and wanted to confirm service is restored."),
    (0, "Hello, calling from the airline. Your flight next week has been rescheduled to depart one hour earlier. Please check your email for details."),
    (0, "This is a reminder from your insurance company that your premium payment is due in ten days. You can pay online or call us to discuss options."),
    (0, "Hi, this is Tom from accounting. Just checking in about the invoice you sent last week. Can you send the updated version when you get a chance?"),
    (0, "Hello, I'm reaching out from the neighborhood association about the community cleanup event this Saturday. Hope to see you there."),
]

def main():
    if not os.path.exists(CSV_PATH):
        print(f"ERROR: {CSV_PATH} not found. Run this script from the ml_training directory.", file=sys.stderr)
        sys.exit(1)

    # Read existing headers
    with open(CSV_PATH, newline="", encoding="utf-8") as f:
        reader = csv.reader(f)
        headers = next(reader)
        existing_count = sum(1 for _ in reader)

    print(f"Existing rows: {existing_count}")
    print(f"Adding {len(ROWS)} new rows...")

    with open(CSV_PATH, "a", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        for label, text in ROWS:
            # Build a row matching the CSV header
            row = {h: "" for h in headers}
            row["text"] = text
            row["label"] = str(label)
            # Audio features default to 0 for text-only samples
            for col in ["avg_rms", "rms_std_dev", "silence_ratio", "had_long_silence",
                        "call_duration_sec", "call_hour", "speaker_switches",
                        "noise_floor_db", "speech_rate_wpm", "dtmf_detected"]:
                if col in row:
                    row[col] = "0"
            writer.writerow([row.get(h, "") for h in headers])

    print(f"Done. CSV now has {existing_count + len(ROWS)} data rows.")
    print(f"\nBreakdown of added rows:")
    from collections import Counter
    counts = Counter(label for label, _ in ROWS)
    labels = {0:"Legitimate",1:"IRS",2:"Tech Support",3:"Bank Fraud",4:"Lottery",
              5:"Social Security",6:"Robocall",7:"Phishing",8:"Insurance",
              9:"Investment",10:"Donation"}
    for lbl in sorted(counts):
        print(f"  {lbl:2d} ({labels[lbl]:16s}): {counts[lbl]:3d} rows")

if __name__ == "__main__":
    main()
