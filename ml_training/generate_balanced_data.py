#!/usr/bin/env python3
"""
Balanced training data generator for VocaGuard scam detector.

Generates ~1000 varied examples per class for labels 0-8,
then combines with existing label-9 (investment scam) data,
downsampled to balance the dataset.

Labels:
  0 = Legitimate
  1 = IRS Scam
  2 = Tech Support Scam
  3 = Bank Fraud
  4 = Lottery / Prize Scam
  5 = Social Security Scam
  6 = Robocall
  7 = Phishing
  8 = Insurance Scam
  9 = Investment Scam
"""

import random
import re
import pandas as pd

random.seed(42)

# ---------------------------------------------------------------------------
# Seed phrases per class
# Each list has diverse examples covering different lengths, tones, keywords.
# ---------------------------------------------------------------------------

SEEDS = {
    0: [  # Legitimate calls
        "Hello this is Dr Smith's office calling to confirm your appointment tomorrow at 3pm",
        "Hi I'm calling from the pharmacy your prescription is ready for pickup",
        "This is a reminder your car service appointment is scheduled for Monday morning",
        "Good morning calling from the school to let you know about the schedule change",
        "Hi this is Mike from the plumbing company following up on your service request",
        "Calling to confirm your delivery will arrive between 2 and 4 this afternoon",
        "This is the library notifying you that your reserved book is now available",
        "Hello calling from the dental office to remind you about your cleaning next week",
        "Hi this is Sarah from HR calling about your benefits enrollment deadline",
        "Good afternoon this is the vet clinic reminding you about your pet's checkup",
        "This is a courtesy call from your internet provider about scheduled maintenance",
        "Hello I'm calling from the real estate agency about the property you inquired about",
        "Hi this is Tom from the repair shop your device is ready to be picked up",
        "Calling to let you know your food delivery is on the way estimated 20 minutes",
        "This is a reminder from your gym membership renewal is coming up next month",
        "Hello this is the airline calling to inform you of a gate change for your flight",
        "Hi calling from the hotel to confirm your reservation for next weekend",
        "Good morning this is the moving company confirming your booking for Saturday",
        "This is customer service following up on your recent support ticket",
        "Hello calling from the city to notify you about road closures in your area",
        "Hi this is the accounting firm reminding you of the upcoming tax filing deadline",
        "Calling from the blood bank to thank you for your recent donation",
        "This is the optometrist office your glasses are ready for collection",
        "Hello calling from the travel agency your itinerary documents are ready",
        "Hi this is the building manager regarding the maintenance work next Tuesday",
    ],
    1: [  # IRS Scam
        "This is the IRS calling you owe back taxes and must pay immediately or face arrest",
        "Internal Revenue Service final notice you have an outstanding tax debt call back now",
        "IRS calling your tax return has been flagged for fraud legal action will be taken",
        "This is a final warning from the IRS you owe money pay now or go to jail",
        "Federal tax authorities are issuing a warrant for your arrest for unpaid taxes",
        "IRS agent calling you must pay your tax debt today or officers will come to your home",
        "This is the IRS you have committed tax fraud and must settle your account immediately",
        "Urgent message from the Internal Revenue Service you owe 3500 dollars in back taxes",
        "IRS final notice failure to respond will result in your arrest and prosecution",
        "This is the tax department calling about your unpaid federal taxes act immediately",
        "You have been selected for an IRS audit irregularities found in your tax filing",
        "IRS calling last chance to pay your overdue taxes before a lien is placed on your assets",
        "Federal government is pressing charges for tax evasion call back to resolve this now",
        "IRS notice your social security number has been used in fraudulent tax filings",
        "This is the IRS collections department you must make a payment arrangement immediately",
        "Final IRS notice an arrest warrant has been issued in your name for tax fraud",
        "Internal Revenue agent calling failure to pay 4200 dollars will result in imprisonment",
        "IRS calling we found discrepancies in your returns you must contact us immediately",
        "Tax fraud charges have been filed against you contact the IRS immediately to resolve",
        "This is your last warning from the federal tax agency pay now or face prosecution",
        "IRS notification your wages will be garnished unless you settle your tax debt today",
        "You owe the IRS money and if you do not call back we will send officers to your location",
        "Federal tax lien alert the IRS is placing a hold on your assets for unpaid taxes",
        "IRS calling please call back immediately to avoid criminal tax fraud charges",
        "This is the IRS your request for extension has been denied you must pay immediately",
    ],
    2: [  # Tech Support Scam
        "This is Microsoft support we detected a virus on your computer call immediately",
        "Apple security team calling your device has been compromised we need remote access",
        "Windows technical support your PC is sending error reports indicating malware infection",
        "Your computer has been hacked our security team detected unauthorized access",
        "Tech support calling we detected suspicious activity on your device act now",
        "Microsoft alert your computer is infected with dangerous spyware call us immediately",
        "This is Google security your account has been accessed from unknown location",
        "Apple support team your iPhone has been compromised download our security app now",
        "Windows defender detected critical errors on your system call support immediately",
        "Your computer is broadcasting your personal data to hackers we can help you stop it",
        "Tech support department we found malware on your device you must act immediately",
        "This is Amazon IT security your account credentials have been stolen contact us now",
        "Norton security alert your subscription has expired your computer is now vulnerable",
        "Microsoft calling we have received error logs from your computer indicating virus",
        "Your device has been flagged by our security systems for suspicious activity",
        "Apple ID security breach detected we need to verify your information to protect you",
        "Windows activation server detected your license has been used fraudulently call us",
        "Tech support calling your firewall has been disabled by malware call to restore",
        "This is your internet service provider calling about malicious traffic from your IP",
        "Security team detected ransomware attempting to encrypt your files call us now",
        "Your router has been compromised allowing hackers to intercept your communications",
        "Microsoft partner calling your computer will be shut down remotely if not fixed now",
        "Tech support division your computer is sending spam emails without your knowledge",
        "Critical system alert your computer has been locked by malware call support to unlock",
        "This is the software licensing department your Windows key has been stolen call us",
    ],
    3: [  # Bank Fraud
        "Bank of America security alert your account has been suspended verify your information",
        "Your bank account has been locked due to suspicious activity confirm your PIN now",
        "Fraud alert on your credit card unusual charges detected verify your account details",
        "Chase bank calling your account will be closed unless you verify your information",
        "Your debit card has been suspended call immediately to confirm your identity",
        "Banking security team your account shows unauthorized transactions verify credentials",
        "Wells Fargo alert your online banking access has been temporarily locked",
        "Your bank account has been flagged for suspicious activity please verify immediately",
        "Credit card security alert a large transaction was attempted confirm or deny now",
        "Bank fraud department calling your account has been compromised act immediately",
        "Your checking account has been suspended due to unusual login activity verify now",
        "Citibank calling your account will be permanently locked unless you confirm your details",
        "Important security message your bank account needs verification to restore access",
        "Bank security team we detected unauthorized access to your account verify now",
        "Your credit card number has been used fraudulently we need to verify your identity",
        "Fraud prevention calling a transfer of 2500 dollars was flagged confirm or deny",
        "Banking alert your account password must be reset immediately to prevent unauthorized use",
        "Security department your bank card has been cloned verify your information to get new card",
        "Bank calling your account has been placed on hold pending identity verification",
        "Suspicious login detected on your online banking account verify your credentials now",
        "Your savings account has been frozen due to a court order call us to resolve this",
        "Bank security calling your wire transfer has been flagged confirm your account details",
        "Fraud alert your bank account has exceeded withdrawal limits verify your identity",
        "Your mobile banking app has been compromised call us to secure your account now",
        "Banking security your account shows signs of identity theft verify your details immediately",
    ],
    4: [  # Lottery / Prize Scam
        "Congratulations you have won the national lottery claim your prize of 50000 dollars now",
        "You are the lucky winner of our sweepstakes call back to collect your cash prize",
        "Winner notification you have been selected to receive a free vacation package",
        "Congratulations you won a brand new car call immediately to arrange delivery",
        "You have been randomly selected as our grand prize winner of 100000 dollars",
        "Free cruise winner alert you have qualified for an all expenses paid vacation",
        "National sweepstakes commission calling you won 25000 dollars claim your prize",
        "Congratulations your entry was selected you have won a free iPhone call to claim",
        "Publishers clearing house calling you are our prize winner of 1 million dollars",
        "You have won a gift card worth 500 dollars call now to activate your reward",
        "Lucky winner notification you have won the overseas lottery claiming is easy",
        "Prize patrol calling you have been selected to win a 75000 dollar home makeover",
        "Congratulations you are our monthly winner call back within 24 hours to claim",
        "Lottery commission calling you have won 15000 dollars in our quarterly drawing",
        "You have been selected to receive a 1000 dollar cash prize no purchase necessary",
        "Mega prize winner alert you won a luxury cruise for two call to confirm your prize",
        "Congratulations your phone number was drawn in our national competition you won",
        "Sweepstakes alert you qualify for a 10000 dollar reward call to claim today",
        "Winner winner you have been selected for our VIP prize package call us now",
        "International lottery winner you must pay a small processing fee to claim your winnings",
        "Cash prize notification you have won the regional sweepstakes call to collect",
        "Congratulations you have been awarded a free shopping spree worth 2000 dollars",
        "Prize department calling your entry has won our annual grand prize drawing",
        "You are a winner in our customer appreciation lottery call to claim your free money",
        "Congratulations the prize committee has selected you for a special cash award",
    ],
    5: [  # Social Security Scam
        "Your social security number has been suspended due to suspicious activity call now",
        "Social Security Administration calling your benefits have been placed on hold",
        "SSN alert your social security number has been linked to criminal activity",
        "This is the Social Security office your number has been used in illegal transactions",
        "Your social security account has been compromised call immediately to verify",
        "SSA calling your social security number is about to be permanently deactivated",
        "Federal benefits office your social security payments have been suspended",
        "Social security fraud detected your number is being used by multiple people",
        "Your SSN has been flagged for money laundering activity call us immediately",
        "Social Security Administration urgent message your account will be terminated",
        "This is federal benefits agency your social security is suspended verify identity",
        "SSN suspension notice your number has been associated with drug trafficking",
        "Social security calling your monthly benefits will stop unless you verify now",
        "Your social security number has been compromised by a data breach call to protect it",
        "Federal agency calling your social security account shows irregular activity",
        "SSA alert your disability benefits have been suspended call to reinstate",
        "Social Security Administration your retirement benefits are at risk call now",
        "Your social security number was found in an illegal transaction verify immediately",
        "SSN fraud alert law enforcement has flagged your number for investigation",
        "Social security office calling we need to verify your identity to restore benefits",
        "Your social security number is being deactivated within 24 hours call to prevent",
        "Federal social security fraud division calling your number has been stolen",
        "SSA urgent notice failure to call back will result in arrest and prosecution",
        "Social security benefits suspension notice call to verify your identity now",
        "Your SSN has been used to open fraudulent accounts call to protect your number",
    ],
    6: [  # Robocall
        "This is an important message regarding your vehicle extended warranty press one now",
        "Do not hang up this automated message contains critical information about your account",
        "Final notice your car warranty is expiring call immediately to avoid losing coverage",
        "Urgent automated message about your health insurance press one to speak with agent",
        "This is your last chance to reduce your credit card interest rate press one now",
        "Automated alert you qualify for a government debt relief program press one",
        "Important notice about your student loan press one to speak with a specialist",
        "This call is about your home security system press one to learn about upgrades",
        "Automated message you may be entitled to compensation press one to claim",
        "Final warning your account needs immediate attention press one for assistance",
        "This is a recorded message about your mortgage refinancing options press one now",
        "Urgent automated notice you qualify for free money from the government press one",
        "Do not ignore this message your subscription service requires action press one",
        "Automated call your medical alert device needs immediate attention press one",
        "This is an important notification regarding your utilities press one now",
        "Last chance automated message about unclaimed benefits you may qualify press one",
        "Urgent press one now to speak with a specialist about your financial situation",
        "This automated system is calling about your expiring insurance policy press one",
        "Important automated alert regarding recent activity on your account press one",
        "Recorded message your property tax relief application needs review press one",
        "This is a final automated notice regarding your outstanding balance press one",
        "Automated government assistance program calling you may qualify press one now",
        "Do not hang up this call contains time sensitive information about your benefits",
        "Urgent automated message your free trial is ending upgrade now press one",
        "This recorded message is about a special offer available to you press one now",
    ],
    7: [  # Phishing
        "Please verify your account information to avoid suspension click the link sent",
        "Your account login has been flagged confirm your password to restore access",
        "Security update required please verify your personal information immediately",
        "Your profile needs to be updated to confirm your identity click the link now",
        "Account verification required provide your login credentials to continue",
        "We need to verify your identity please confirm your email and password now",
        "Your account access will be revoked unless you verify your information today",
        "Important security update please confirm your account details to stay protected",
        "Suspicious login attempt on your account verify your credentials to secure it",
        "Your password must be reset immediately to protect your account verify now",
        "Account alert please confirm your billing information to avoid service interruption",
        "We detected unusual activity please verify your identity by confirming your details",
        "Your account has been compromised please confirm your security questions now",
        "Verify your account to unlock full access provide your information to continue",
        "Account security check required please confirm your personal details immediately",
        "Your login credentials have been exposed verify your account to secure it now",
        "Please update and verify your account information to prevent unauthorized access",
        "Security verification needed confirm your address and date of birth to proceed",
        "Your account shows suspicious activity please verify your identity to restore access",
        "Confirm your account ownership by providing your username and password now",
        "Identity verification required please provide your social security number to confirm",
        "Account locked for security please verify your information to unlock immediately",
        "We need to verify your payment information to process your recent order confirm now",
        "Your account requires immediate verification to prevent permanent suspension",
        "Please confirm your account details to complete the security update required now",
    ],
    10: [  # Donation Fraud
        "This is the Red Cross calling please donate to help victims of the recent disaster",
        "Charity foundation calling your donation will help children in need donate now",
        "We are collecting donations for disaster relief please contribute any amount today",
        "Help victims of the flood donate to our relief fund tax deductible donation",
        "Police benevolent fund calling please support your local officers with a donation",
        "Children cancer charity calling please donate to help sick children fight cancer",
        "Firefighters relief fund calling please make a contribution to support our heroes",
        "Humanitarian relief organization calling donate now to feed starving families",
        "Veterans charity calling please donate to support wounded veterans in need",
        "Animal rescue charity calling your donation will save abandoned animals today",
        "Earthquake relief fund calling we need your donation immediately to help survivors",
        "Nonprofit organization calling your tax deductible donation helps homeless people",
        "Fundraising call for local hospital please donate to help patients in need",
        "Disaster relief charity calling make a contribution to help flood victims today",
        "International aid organization calling donate to help refugees in crisis now",
        "Charity drive calling your donation goes directly to hungry children overseas",
        "Community fundraiser calling please support local families devastated by fire",
        "Relief organization calling your donation helps victims recover from tragedy",
        "Charity calling we accept credit card donations please have your card ready",
        "Nonprofit calling your generous donation is tax deductible act before year end",
        "Cancer research foundation please donate to help find a cure contribute now",
        "Hunger relief charity calling donate today and we will match your contribution",
        "Disaster fund calling urgent need for donations to help storm survivors",
        "Charity organization calling make a contribution to support our worthy cause",
        "Relief fund calling every dollar donated goes to help victims of this tragedy",
    ],
    8: [  # Insurance Scam
        "Calling about your health insurance you may qualify for a free plan with no premium",
        "Medicare enrollment calling you qualify for additional free benefits act now",
        "Your health insurance plan is about to change you need to confirm your coverage",
        "Medicaid calling you have been approved for free supplemental insurance",
        "Health plan specialist calling you may save money on your insurance payment now",
        "Open enrollment ends soon call to claim your free health insurance benefits",
        "Insurance company calling your policy needs to be updated to avoid coverage gaps",
        "You qualify for zero dollar health insurance with prescription coverage call now",
        "Medicare advantage calling you have unclaimed benefits worth thousands of dollars",
        "Health insurance alert your plan is being discontinued you must enroll in new plan",
        "Free life insurance offer you have been pre-approved for 50000 dollar coverage",
        "Insurance enrollment specialist your employer plan has changed act now for coverage",
        "Affordable care act calling you qualify for subsidized health insurance payment help",
        "Your insurance premium can be reduced to almost nothing call to find out how",
        "Medicare supplement plan calling you are eligible for dental and vision coverage free",
        "Health insurance marketplace your enrollment period is ending sign up now",
        "Insurance specialist calling about your lapsed policy reinstate now to maintain coverage",
        "You qualify for a government insurance subsidy that covers your entire payment",
        "Free dental plan offer seniors qualify for no cost dental coverage call to enroll",
        "Insurance fraud department calling your policy has been flagged verify your details",
        "Your current health plan will terminate unless you switch to the new approved plan",
        "Medicare calling your benefits card has been deactivated verify to reactivate now",
        "Special insurance offer for qualifying customers reduce your payment by 80 percent",
        "Health insurance enrollment deadline calling make sure you have coverage by end of month",
        "Your auto insurance has expired you are now driving illegally call to reinstate payment",
    ],
}

# ---------------------------------------------------------------------------
# Augmentation helpers
# ---------------------------------------------------------------------------

URGENCY = ['immediately', 'right now', 'at once', 'urgently', 'without delay', 'today', 'now']
AMOUNTS = ['500', '1000', '2500', '3500', '4200', '5000', '7500', '10000', '15000', '25000']
AGENTS = ['agent', 'officer', 'representative', 'specialist', 'department', 'authority']

def vary_text(text: str) -> str:
    """Apply random surface-level variations to a text."""
    ops = random.sample(range(5), k=random.randint(1, 3))

    if 0 in ops:
        # Replace urgency words
        for word in ['immediately', 'urgently', 'right now', 'at once', 'now']:
            if word in text.lower():
                text = re.sub(word, random.choice(URGENCY), text, count=1, flags=re.IGNORECASE)
                break

    if 1 in ops:
        # Replace dollar amounts
        text = re.sub(r'\d{3,6}', random.choice(AMOUNTS), text, count=1)

    if 2 in ops:
        # Occasionally add trailing urgency
        if random.random() < 0.4:
            text = text.rstrip('.') + ' ' + random.choice([
                'do not delay.', 'act now.', 'time is running out.',
                'this is your final notice.', 'failure to respond will have consequences.'
            ])

    if 3 in ops:
        # Occasionally uppercase a keyword
        keywords = ['irs', 'bank', 'microsoft', 'apple', 'social security', 'medicare',
                    'urgent', 'suspended', 'verify', 'immediately', 'warning']
        for kw in keywords:
            if kw in text.lower() and random.random() < 0.3:
                text = re.sub(kw, kw.upper(), text, count=1, flags=re.IGNORECASE)
                break

    if 4 in ops:
        # Add filler at start
        fillers = ['Hello,', 'Hi,', 'Good morning,', 'Good afternoon,', 'Attention,',
                   'Important:', 'Warning:', 'Notice:', 'Alert:']
        if not any(text.startswith(f) for f in fillers):
            text = random.choice(fillers) + ' ' + text[0].lower() + text[1:]

    return text


def generate_examples(label: int, seeds: list, target: int) -> list:
    """
    Expand seed phrases into `target` varied examples for a given label.
    """
    examples = list(seeds)  # start with seeds
    while len(examples) < target:
        base = random.choice(seeds)
        examples.append(vary_text(base))
    random.shuffle(examples)
    return [(text, label) for text in examples[:target]]


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    TARGET_PER_CLASS = 1200  # ~same for every class including label 9

    print("VocaGuard Balanced Data Generator")
    print("=" * 50)

    all_examples = []

    # Generate for labels 0-8
    for label, seeds in SEEDS.items():
        examples = generate_examples(label, seeds, TARGET_PER_CLASS)
        all_examples.extend(examples)
        print(f"Label {label}: generated {len(examples)} examples")

    # Load existing label-9 data and downsample to TARGET_PER_CLASS
    print(f"\nLoading existing training_data.csv for label 9 ...")
    try:
        existing = pd.read_csv('training_data.csv')
        label9 = existing[existing['label'] == 9].sample(
            n=min(TARGET_PER_CLASS, len(existing[existing['label'] == 9])),
            random_state=42
        )
        for _, row in label9.iterrows():
            all_examples.append((row['text'], 9))
        print(f"Label 9: sampled {len(label9)} examples from existing data")
    except FileNotFoundError:
        print("  WARNING: training_data.csv not found, generating label 9 from seeds too")

    # Build dataframe
    df = pd.DataFrame(all_examples, columns=['text', 'label'])
    df = df.sample(frac=1, random_state=42).reset_index(drop=True)

    df.to_csv('training_data.csv', index=False)

    print(f"\nSaved {len(df)} total examples to training_data.csv")
    print("\nBreakdown:")
    print(df['label'].value_counts().sort_index().to_string())
    print("\nReady to train — run: python train_model.py")


if __name__ == '__main__':
    main()
