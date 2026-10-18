-- =====================================================================
-- PURPOSE : SAMPLE / SEED DATA for the Precision Farming Advisory Service.
--           Re-runnable: INSERT IGNORE (unique keys) and idempotent UPDATEs,
--           never a DELETE or a DROP. Rows that are not real-world facts are
--           marked SAMPLE in the UI.
-- HOW TO RUN (once, from the project root, AFTER schema_new.sql):
--   mysql -u advisory -padvisory123 advisory_db < src/main/resources/data_new.sql
-- If this breaks, check:
--   - duplicate key errors -> safe to ignore; INSERT IGNORE skips existing rows
--   - districts empty       -> the `districts` table must exist (schema_new.sql)
-- =====================================================================

USE advisory_db;

-- ---------------------------------------------------------------------
-- 1. DISTRICTS - the 36 districts of Maharashtra (STEP 2 dropdown + STEP 4 map).
--    lat/lon are APPROXIMATE district-headquarters coordinates (marked SAMPLE
--    in the UI map legend) and are only used to place a dot on the SVG map.
-- ---------------------------------------------------------------------
INSERT IGNORE INTO districts (code, name_en, name_hi, name_mr, lat, lon) VALUES
('ahmednagar',       'Ahmednagar',        'अहमदनगर',        'अहमदनगर',        19.09, 74.74),
('akola',            'Akola',             'अकोला',          'अकोला',          20.70, 77.00),
('amravati',         'Amravati',          'अमरावती',        'अमरावती',        20.93, 77.75),
('aurangabad',       'Aurangabad',        'औरंगाबाद',       'औरंगाबाद',       19.88, 75.34),
('beed',             'Beed',              'बीड',            'बीड',            18.99, 75.76),
('bhandara',         'Bhandara',          'भंडारा',          'भंडारा',         21.17, 79.65),
('buldhana',         'Buldhana',          'बुलढाणा',        'बुलढाणा',        20.53, 76.18),
('chandrapur',       'Chandrapur',        'चंद्रपुर',        'चंद्रपूर',        19.95, 79.30),
('dhule',            'Dhule',             'धुले',           'धुळे',           20.90, 74.77),
('gadchiroli',       'Gadchiroli',        'गडचिरोली',       'गडचिरोली',       20.18, 80.00),
('gondia',           'Gondia',            'गोंदिया',         'गोंदिया',        21.46, 80.19),
('hingoli',          'Hingoli',           'हिंगोली',         'हिंगोली',        19.72, 77.15),
('jalgaon',          'Jalgaon',           'जलगांव',         'जळगाव',          21.01, 75.56),
('jalna',            'Jalna',             'जालना',          'जालना',          19.84, 75.88),
('kolhapur',         'Kolhapur',          'कोल्हापुर',       'कोल्हापूर',       16.70, 74.24),
('latur',            'Latur',             'लातूर',          'लातूर',          18.40, 76.57),
('mumbai-city',      'Mumbai City',       'मुंबई शहर',       'मुंबई शहर',       18.94, 72.83),
('mumbai-suburban',  'Mumbai Suburban',   'मुंबई उपनगर',     'मुंबई उपनगर',     19.11, 72.85),
('nagpur',           'Nagpur',            'नागपुर',         'नागपूर',         21.15, 79.09),
('nanded',           'Nanded',            'नांदेड',          'नांदेड',          19.15, 77.32),
('nandurbar',        'Nandurbar',         'नंदुरबार',        'नंदुरबार',        21.37, 74.24),
('nashik',           'Nashik',            'नाशिक',          'नाशिक',          19.99, 73.79),
('osmanabad',        'Osmanabad',         'उस्मानाबाद',      'उस्मानाबाद',      18.19, 76.04),
('palghar',          'Palghar',           'पालघर',          'पालघर',          19.70, 72.77),
('parbhani',         'Parbhani',          'परभनी',          'परभणी',          19.27, 76.78),
('pune',             'Pune',              'पुणे',           'पुणे',           18.52, 73.86),
('raigad',           'Raigad',            'रायगढ़',          'रायगड',          18.52, 73.18),
('ratnagiri',        'Ratnagiri',         'रत्नागिरी',       'रत्नागिरी',       16.99, 73.30),
('sangli',           'Sangli',            'सांगली',         'सांगली',         16.85, 74.58),
('satara',           'Satara',            'सतारा',          'सातारा',         17.69, 74.00),
('sindhudurg',       'Sindhudurg',        'सिंधुदुर्ग',       'सिंधुदुर्ग',       16.00, 73.53),
('solapur',          'Solapur',           'सोलापुर',         'सोलापूर',         17.66, 75.91),
('thane',            'Thane',             'ठाणे',           'ठाणे',           19.22, 72.98),
('wardha',           'Wardha',            'वर्धा',           'वर्धा',           20.75, 78.60),
('washim',           'Washim',            'वाशिम',          'वॉशिम',          20.11, 77.13),
('yavatmal',         'Yavatmal',          'यवतमाल',         'यवतमाळ',         20.39, 78.13);

-- ---------------------------------------------------------------------
-- 2. APPROVE the two SAMPLE labs that ship with the demo, and give them the
--    taluka + status that STEP 2 introduced. Idempotent, so it is safe to
--    re-run. (A real lab registered through /register/lab stays PENDING.)
-- ---------------------------------------------------------------------
UPDATE labs SET status = 'APPROVED' WHERE id IN (1, 2);
UPDATE labs SET taluka = 'Panvel'  WHERE id = 1 AND (taluka IS NULL OR taluka = '');
UPDATE labs SET taluka = 'Haveli'  WHERE id = 2 AND (taluka IS NULL OR taluka = '');

-- ---------------------------------------------------------------------
-- 3. SCHEMES (STEP 3) - the seven government schemes shown by /schemes.
--    The three demo rows already existed; give them a scheme_key + order,
--    then add the four missing schemes. `sort_order` drives the display
--    order and the tile number. Re-runnable: UPDATE + INSERT..WHERE NOT EXISTS.
-- ---------------------------------------------------------------------
UPDATE schemes SET scheme_key = 'pmkisan', sort_order = 1 WHERE name_key = 'scheme.pmkisan.name';
UPDATE schemes SET scheme_key = 'mahadbt', sort_order = 2 WHERE name_key = 'scheme.mahadbt.name';
UPDATE schemes SET scheme_key = 'pmfby',   sort_order = 3 WHERE name_key = 'scheme.pmfby.name';

INSERT INTO schemes (scheme_key, name_key, description_key, eligibility_key,
                     documents_key, contact_info_key, official_link, sort_order)
SELECT 'soilhealth', 'scheme.soilhealth.name', 'scheme.soilhealth.desc',
       'scheme.soilhealth.elig', 'scheme.soilhealth.docs', 'scheme.soilhealth.contact',
       'https://soilhealth.dac.gov.in', 4 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM schemes WHERE scheme_key = 'soilhealth');

INSERT INTO schemes (scheme_key, name_key, description_key, eligibility_key,
                     documents_key, contact_info_key, official_link, sort_order)
SELECT 'mahabhulekh', 'scheme.mahabhulekh.name', 'scheme.mahabhulekh.desc',
       'scheme.mahabhulekh.elig', 'scheme.mahabhulekh.docs', 'scheme.mahabhulekh.contact',
       'https://bhulekh.mahabhumi.gov.in', 5 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM schemes WHERE scheme_key = 'mahabhulekh');

INSERT INTO schemes (scheme_key, name_key, description_key, eligibility_key,
                     documents_key, contact_info_key, official_link, sort_order)
SELECT 'kcc', 'scheme.kcc.name', 'scheme.kcc.desc',
       'scheme.kcc.elig', 'scheme.kcc.docs', 'scheme.kcc.contact',
       'https://agriwelfare.gov.in', 6 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM schemes WHERE scheme_key = 'kcc');

INSERT INTO schemes (scheme_key, name_key, description_key, eligibility_key,
                     documents_key, contact_info_key, official_link, sort_order)
SELECT 'farmerid', 'scheme.farmerid.name', 'scheme.farmerid.desc',
       'scheme.farmerid.elig', 'scheme.farmerid.docs', 'scheme.farmerid.contact',
       'https://agristack.gov.in', 7 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM schemes WHERE scheme_key = 'farmerid');

-- ---------------------------------------------------------------------
-- 4. SCHEME TEXT (STEP 3) - the readable content of each scheme in all
--    three languages, so /schemes/{key} shows real Hindi and Marathi too.
--    Stored in the DB (not message files) so the OWNER can edit it in STEP 7.
--    Re-runnable: the (scheme_id, lang) unique key makes INSERT IGNORE safe.
--    NOTE: this is a simplified citizen guide written for the demo; the UI
--    shows a SAMPLE note telling the farmer to confirm details on the
--    official portal and at the local office.
-- ---------------------------------------------------------------------

-- PM-KISAN (en, hi, mr)
INSERT IGNORE INTO scheme_text (scheme_id, lang, scheme_name, what_text, who_text, docs_text, steps_text, where_text)
SELECT id, 'en', 'PM-KISAN',
 'Central government income support. Money is sent directly to the bank account of eligible landholding farmer families in three instalments a year.',
 'Landholding farmer families with cultivable land, subject to the exclusion rules of the scheme.',
 'Aadhaar card, 7/12 and 8A land records, bank passbook, Aadhaar-linked mobile number, passport size photo.',
 'Apply or self-register on the PM-KISAN portal or through the local Patwari or agriculture office, complete e-KYC, local verification is done, then the benefit is credited to the bank account.',
 'CSC centre, Taluka Agriculture Office, or online on the PM-KISAN portal.'
FROM schemes WHERE scheme_key = 'pmkisan';

INSERT IGNORE INTO scheme_text (scheme_id, lang, scheme_name, what_text, who_text, docs_text, steps_text, where_text)
SELECT id, 'hi', 'पीएम-किसान',
 'केंद्र सरकार की आय सहायता योजना। पात्र भूमिधारक किसान परिवारों के बैंक खाते में साल में तीन किस्तों में सीधे पैसा भेजा जाता है।',
 'खेती योग्य भूमि वाले भूमिधारक किसान परिवार, योजना के अपवर्जन नियमों के अधीन।',
 'आधार कार्ड, 7/12 और 8A भूमि रिकॉर्ड, बैंक पासबुक, आधार से जुड़ा मोबाइल नंबर, पासपोर्ट आकार का फोटो।',
 'पीएम-किसान पोर्टल पर या स्थानीय तलाठी या कृषि कार्यालय के माध्यम से आवेदन करें, ई-केवाईसी पूरी करें, स्थानीय सत्यापन होता है, फिर लाभ बैंक खाते में जमा होता है।',
 'सीएससी केंद्र, तालुका कृषि कार्यालय, या पीएम-किसान पोर्टल पर ऑनलाइन।'
FROM schemes WHERE scheme_key = 'pmkisan';

INSERT IGNORE INTO scheme_text (scheme_id, lang, scheme_name, what_text, who_text, docs_text, steps_text, where_text)
SELECT id, 'mr', 'पीएम-किसान',
 'केंद्र सरकारची उत्पन्न सहाय्य योजना. पात्र भूधारक शेतकरी कुटुंबांच्या बँक खात्यात वर्षाला तीन हप्त्यांत थेट पैसे पाठवले जातात.',
 'लागवडीयोग्य जमीन असलेली भूधारक शेतकरी कुटुंबे, योजनेच्या अपवाद नियमांच्या अधीन.',
 'आधार कार्ड, 7/12 आणि 8A जमीन नोंदी, बँक पासबुक, आधारशी जोडलेला मोबाइल क्रमांक, पासपोर्ट आकाराचा फोटो.',
 'पीएम-किसान पोर्टलवर किंवा स्थानिक तलाठी किंवा कृषी कार्यालयामार्फत अर्ज करा, ई-केवायसी पूर्ण करा, स्थानिक पडताळणी होते, नंतर लाभ बँक खात्यात जमा होतो.',
 'सीएससी केंद्र, तालुका कृषी कार्यालय, किंवा पीएम-किसान पोर्टलवर ऑनलाइन.'
FROM schemes WHERE scheme_key = 'pmkisan';

-- MahaDBT (en, hi, mr)
INSERT IGNORE INTO scheme_text (scheme_id, lang, scheme_name, what_text, who_text, docs_text, steps_text, where_text)
SELECT id, 'en', 'MahaDBT',
 'The Maharashtra Direct Benefit Transfer portal. One login gives access to many state agriculture and other subsidy schemes.',
 'Residents of Maharashtra who meet the eligibility of the individual scheme they apply for.',
 'Aadhaar, 7/12 and 8A land extracts, bank passbook, income certificate where the scheme asks for it, photo.',
 'Create an account on MahaDBT, choose the scheme, fill the application, upload the documents, submit it and later track the status online.',
 'Online on MahaDBT, or a CSC centre / Taluka Agriculture Office for help.'
FROM schemes WHERE scheme_key = 'mahadbt';

INSERT IGNORE INTO scheme_text (scheme_id, lang, scheme_name, what_text, who_text, docs_text, steps_text, where_text)
SELECT id, 'hi', 'महाडीबीटी',
 'महाराष्ट्र का डायरेक्ट बेनिफिट ट्रांसफर पोर्टल। एक लॉगिन से कई राज्य कृषि और अन्य सब्सिडी योजनाओं तक पहुंच मिलती है।',
 'महाराष्ट्र के निवासी जो जिस योजना के लिए आवेदन करते हैं उसकी पात्रता पूरी करते हैं।',
 'आधार, 7/12 और 8A उतारा, बैंक पासबुक, जहां योजना मांगे वहां आय प्रमाण पत्र, फोटो।',
 'महाडीबीटी पर खाता बनाएं, योजना चुनें, आवेदन भरें, दस्तावेज़ अपलोड करें, सबमिट करें और बाद में स्थिति ऑनलाइन देखें।',
 'महाडीबीटी पर ऑनलाइन, या सहायता के लिए सीएससी केंद्र या तालुका कृषि कार्यालय।'
FROM schemes WHERE scheme_key = 'mahadbt';

INSERT IGNORE INTO scheme_text (scheme_id, lang, scheme_name, what_text, who_text, docs_text, steps_text, where_text)
SELECT id, 'mr', 'महाडीबीटी',
 'महाराष्ट्राचे डायरेक्ट बेनिफिट ट्रान्सफर पोर्टल. एका लॉगिनने अनेक राज्य कृषी व इतर अनुदान योजनांमध्ये प्रवेश मिळतो.',
 'महाराष्ट्रातील रहिवासी जे ज्या योजनेसाठी अर्ज करतात तिची पात्रता पूर्ण करतात.',
 'आधार, 7/12 आणि 8A उतारा, बँक पासबुक, योजना मागत असेल तर उत्पन्नाचा दाखला, फोटो.',
 'महाडीबीटीवर खाते तयार करा, योजना निवडा, अर्ज भरा, कागदपत्रे अपलोड करा, सबमिट करा आणि नंतर स्थिती ऑनलाइन पहा.',
 'महाडीबीटीवर ऑनलाइन, किंवा मदतीसाठी सीएससी केंद्र किंवा तालुका कृषी कार्यालय.'
FROM schemes WHERE scheme_key = 'mahadbt';

-- PM Fasal Bima Yojana (en, hi, mr)
INSERT IGNORE INTO scheme_text (scheme_id, lang, scheme_name, what_text, who_text, docs_text, steps_text, where_text)
SELECT id, 'en', 'PM Fasal Bima Yojana',
 'A crop insurance scheme that covers farmers for loss of the notified crop due to natural calamities, pests and diseases.',
 'All farmers growing a notified crop in a notified area, including tenant farmers and sharecroppers who have the proper records.',
 'Aadhaar, land records (7/12 and 8A) or tenancy proof, bank passbook, sowing certificate, photo.',
 'Apply through the bank, the CSC or the PMFBY portal before the cut-off date, pay the farmer share of the premium; after a loss is reported and surveyed the insurance company settles the claim.',
 'Bank branch, CSC centre, or online on the PMFBY portal.'
FROM schemes WHERE scheme_key = 'pmfby';

INSERT IGNORE INTO scheme_text (scheme_id, lang, scheme_name, what_text, who_text, docs_text, steps_text, where_text)
SELECT id, 'hi', 'पीएम फसल बीमा योजना',
 'फसल बीमा योजना जो प्राकृतिक आपदाओं, कीटों और बीमारियों से अधिसूचित फसल के नुकसान पर किसान को सुरक्षा देती है।',
 'अधिसूचित क्षेत्र में अधिसूचित फसल उगाने वाले सभी किसान, जिनमें उचित रिकॉर्ड वाले किरायेदार किसान और बटाईदार भी शामिल हैं।',
 'आधार, भूमि रिकॉर्ड (7/12 और 8A) या किरायेदारी का प्रमाण, बैंक पासबुक, बुवाई प्रमाणपत्र, फोटो।',
 'अंतिम तिथि से पहले बैंक, सीएससी या पीएमएफबीवाई पोर्टल से आवेदन करें, किसान का हिस्सा प्रीमियम जमा करें; नुकसान की सूचना और सर्वेक्षण के बाद बीमा कंपनी दावा निपटाती है।',
 'बैंक शाखा, सीएससी केंद्र, या पीएमएफबीवाई पोर्टल पर ऑनलाइन।'
FROM schemes WHERE scheme_key = 'pmfby';

INSERT IGNORE INTO scheme_text (scheme_id, lang, scheme_name, what_text, who_text, docs_text, steps_text, where_text)
SELECT id, 'mr', 'पीएम पीक विमा योजना',
 'नैसर्गिक आपत्ती, कीड व रोगांमुळे अधिसूचित पिकाचे नुकसान झाल्यास शेतकऱ्याला संरक्षण देणारी पीक विमा योजना.',
 'अधिसूचित भागात अधिसूचित पीक घेणारे सर्व शेतकरी, योग्य नोंदी असलेले भाडेकरू व बटाईदार यांचा समावेश.',
 'आधार, जमीन नोंदी (7/12 आणि 8A) किंवा भाडेकराराचा पुरावा, बँक पासबुक, पेरणीचा दाखला, फोटो.',
 'अंतिम तारखेपूर्वी बँक, सीएससी किंवा पीएमएफबीवाय पोर्टलमार्फत अर्ज करा, शेतकऱ्याचा हिस्सा हप्ता भरा; नुकसान कळवल्यावर व पाहणी झाल्यावर विमा कंपनी दावा निकाली काढते.',
 'बँक शाखा, सीएससी केंद्र, किंवा पीएमएफबीवाय पोर्टलवर ऑनलाइन.'
FROM schemes WHERE scheme_key = 'pmfby';

-- Soil Health Card (en, hi, mr)
INSERT IGNORE INTO scheme_text (scheme_id, lang, scheme_name, what_text, who_text, docs_text, steps_text, where_text)
SELECT id, 'en', 'Soil Health Card',
 'Gives a farmer a soil health card with crop-wise advice on nutrients and fertiliser doses after the soil has been tested.',
 'All farmers who own or cultivate agricultural land.',
 'Aadhaar, 7/12 and 8A, mobile number, photo.',
 'Register at the soil testing lab or agriculture office, collect the soil sample as instructed, the lab tests it, and the soil health card is issued with recommendations.',
 'Soil testing lab, Taluka Agriculture Office, or Krishi Vigyan Kendra.'
FROM schemes WHERE scheme_key = 'soilhealth';

INSERT IGNORE INTO scheme_text (scheme_id, lang, scheme_name, what_text, who_text, docs_text, steps_text, where_text)
SELECT id, 'hi', 'मृदा स्वास्थ्य कार्ड',
 'मिट्टी की जांच के बाद किसान को फसल-वार पोषक तत्वों और उर्वरक मात्रा की सलाह के साथ मृदा स्वास्थ्य कार्ड दिया जाता है।',
 'कृषि भूमि के सभी मालिक या काश्तकार किसान।',
 'आधार, 7/12 और 8A, मोबाइल नंबर, फोटो।',
 'मृदा परीक्षण लैब या कृषि कार्यालय में पंजीकरण करें, निर्देशानुसार मिट्टी का नमूना लें, लैब जांच करती है, और सिफारिशों के साथ मृदा स्वास्थ्य कार्ड जारी होता है।',
 'मृदा परीक्षण लैब, तालुका कृषि कार्यालय, या कृषि विज्ञान केंद्र।'
FROM schemes WHERE scheme_key = 'soilhealth';

INSERT IGNORE INTO scheme_text (scheme_id, lang, scheme_name, what_text, who_text, docs_text, steps_text, where_text)
SELECT id, 'mr', 'मृदा आरोग्य कार्ड',
 'माती तपासणीनंतर शेतकऱ्याला पिकानुसार अन्नद्रव्ये व खतमात्रा यांचा सल्ला देणारे मृदा आरोग्य कार्ड दिले जाते.',
 'कृषी जमिनीचे सर्व मालक किंवा लागवड करणारे शेतकरी.',
 'आधार, 7/12 आणि 8A, मोबाइल क्रमांक, फोटो.',
 'माती परीक्षण प्रयोगशाळा किंवा कृषी कार्यालयात नोंदणी करा, सूचनेनुसार मातीचा नमुना घ्या, प्रयोगशाळा तपासणी करते, आणि शिफारशींसह मृदा आरोग्य कार्ड दिले जाते.',
 'माती परीक्षण प्रयोगशाळा, तालुका कृषी कार्यालय, किंवा कृषी विज्ञान केंद्र.'
FROM schemes WHERE scheme_key = 'soilhealth';

-- Mahabhulekh 7/12 and 8A (en, hi, mr)
INSERT IGNORE INTO scheme_text (scheme_id, lang, scheme_name, what_text, who_text, docs_text, steps_text, where_text)
SELECT id, 'en', 'Mahabhulekh (7/12 and 8A)',
 'The online land records service of Maharashtra. It shows the 7/12 extract (record of rights) and the 8A (holding) for a survey number.',
 'Any landholder or citizen who needs land records for a village.',
 'Survey or gat number, village name, taluka and district. Aadhaar is needed only for some related services.',
 'Open the Mahabhulekh portal, choose the district and taluka, enter the village and survey number, then view or download the 7/12 and 8A.',
 'Online on Mahabhulekh. For corrections or mutations go to the Talathi or Tahsildar office.'
FROM schemes WHERE scheme_key = 'mahabhulekh';

INSERT IGNORE INTO scheme_text (scheme_id, lang, scheme_name, what_text, who_text, docs_text, steps_text, where_text)
SELECT id, 'hi', 'महाभूलेख (7/12 और 8A)',
 'महाराष्ट्र की ऑनलाइन भूमि रिकॉर्ड सेवा। यह किसी सर्वे नंबर का 7/12 उतारा (अधिकार अभिलेख) और 8A (जोत) दिखाती है।',
 'कोई भी भूमिधारक या नागरिक जिसे किसी गांव का भूमि रिकॉर्ड चाहिए।',
 'सर्वे या गट नंबर, गांव का नाम, तालुका और जिला। आधार केवल कुछ संबंधित सेवाओं के लिए चाहिए।',
 'महाभूलेख पोर्टल खोलें, जिला और तालुका चुनें, गांव और सर्वे नंबर भरें, फिर 7/12 और 8A देखें या डाउनलोड करें।',
 'महाभूलेख पर ऑनलाइन। सुधार या फेरफार के लिए तलाठी या तहसीलदार कार्यालय जाएं।'
FROM schemes WHERE scheme_key = 'mahabhulekh';

INSERT IGNORE INTO scheme_text (scheme_id, lang, scheme_name, what_text, who_text, docs_text, steps_text, where_text)
SELECT id, 'mr', 'महाभूलेख (7/12 आणि 8A)',
 'महाराष्ट्राची ऑनलाइन जमीन नोंद सेवा. ही एखाद्या सर्व्हे क्रमांकाचा 7/12 उतारा (हक्क नोंद) आणि 8A (ताबा) दाखवते.',
 'एखाद्या गावाची जमीन नोंद हवी असलेला कोणताही भूधारक किंवा नागरिक.',
 'सर्व्हे किंवा गट क्रमांक, गावाचे नाव, तालुका आणि जिल्हा. आधार फक्त काही संबंधित सेवांसाठी लागतो.',
 'महाभूलेख पोर्टल उघडा, जिल्हा आणि तालुका निवडा, गाव आणि सर्व्हे क्रमांक टाका, नंतर 7/12 आणि 8A पहा किंवा डाउनलोड करा.',
 'महाभूलेखवर ऑनलाइन. दुरुस्ती किंवा फेरफारासाठी तलाठी किंवा तहसीलदार कार्यालयात जा.'
FROM schemes WHERE scheme_key = 'mahabhulekh';

-- Kisan Credit Card (en, hi, mr)
INSERT IGNORE INTO scheme_text (scheme_id, lang, scheme_name, what_text, who_text, docs_text, steps_text, where_text)
SELECT id, 'en', 'Kisan Credit Card',
 'Gives farmers timely credit for crop production and for allied activities at a concessional rate of interest.',
 'Owner cultivators, tenant farmers, oral lessees and sharecroppers, and farmers in animal husbandry or fisheries.',
 'Aadhaar, land records (7/12 and 8A) or tenancy proof, bank passbook and KYC, photo.',
 'Apply at any bank branch or through the card application form, the bank appraises and sanctions a credit limit, and the card is issued for use at the branch and ATM.',
 'Any bank branch - co-operative, regional rural or commercial bank.'
FROM schemes WHERE scheme_key = 'kcc';

INSERT IGNORE INTO scheme_text (scheme_id, lang, scheme_name, what_text, who_text, docs_text, steps_text, where_text)
SELECT id, 'hi', 'किसान क्रेडिट कार्ड',
 'किसान को फसल उत्पादन और संबद्ध गतिविधियों के लिए रियायती ब्याज दर पर समय पर ऋण देता है।',
 'भूमि के मालिक काश्तकार, किरायेदार किसान, मौखिक पट्टेदार और बटाईदार, तथा पशुपालन या मत्स्य पालन करने वाले किसान।',
 'आधार, भूमि रिकॉर्ड (7/12 और 8A) या किरायेदारी का प्रमाण, बैंक पासबुक और केवाईसी, फोटो।',
 'किसी भी बैंक शाखा में या कार्ड आवेदन फॉर्म से आवेदन करें, बैंक मूल्यांकन कर ऋण सीमा स्वीकृत करता है, और कार्ड शाखा व एटीएम में उपयोग के लिए जारी होता है।',
 'कोई भी बैंक शाखा - सहकारी, क्षेत्रीय ग्रामीण या वाणिज्यिक बैंक।'
FROM schemes WHERE scheme_key = 'kcc';

INSERT IGNORE INTO scheme_text (scheme_id, lang, scheme_name, what_text, who_text, docs_text, steps_text, where_text)
SELECT id, 'mr', 'किसान क्रेडिट कार्ड',
 'शेतकऱ्याला पीक उत्पादन व संलग्न व्यवसायांसाठी सवलतीच्या व्याजदराने वेळीच कर्ज देते.',
 'जमिनीचे मालक लागवड करणारे, भाडेकरू शेतकरी, तोंडी भाडेकरू व बटाईदार, तसेच पशुपालन किंवा मत्स्यपालन करणारे शेतकरी.',
 'आधार, जमीन नोंदी (7/12 आणि 8A) किंवा भाडेकराराचा पुरावा, बँक पासबुक व केवायसी, फोटो.',
 'कोणत्याही बँक शाखेत किंवा कार्ड अर्ज फॉर्ममार्फत अर्ज करा, बँक मूल्यांकन करून कर्ज मर्यादा मंजूर करते, आणि कार्ड शाखा व एटीएममध्ये वापरण्यासाठी दिले जाते.',
 'कोणतीही बँक शाखा - सहकारी, प्रादेशिक ग्रामीण किंवा व्यापारी बँक.'
FROM schemes WHERE scheme_key = 'kcc';

-- Farmer ID / Agristack (en, hi, mr)
INSERT IGNORE INTO scheme_text (scheme_id, lang, scheme_name, what_text, who_text, docs_text, steps_text, where_text)
SELECT id, 'en', 'Farmer ID (Agristack)',
 'A Farmer ID is a unique digital identity for a farmer, built by linking land and personal records, to make it easier to get scheme benefits.',
 'Farmers who want a digital identity linked to their land and personal records.',
 'Aadhaar, 7/12 and 8A land records, bank account details, mobile number.',
 'The ID is created by the government by linking Aadhaar, land records and bank details. Farmers can check or verify it on the Agristack portal or at the local agriculture office when the drive is running.',
 'Online on Agristack, or through the Taluka Agriculture Office when the programme is active in the district.'
FROM schemes WHERE scheme_key = 'farmerid';

INSERT IGNORE INTO scheme_text (scheme_id, lang, scheme_name, what_text, who_text, docs_text, steps_text, where_text)
SELECT id, 'hi', 'किसान आईडी (एग्रीस्टैक)',
 'किसान आईडी किसान की एक विशिष्ट डिजिटल पहचान है, जो भूमि और व्यक्तिगत रिकॉर्ड जोड़कर बनाई जाती है, ताकि योजना लाभ मिलना आसान हो।',
 'वे किसान जो अपनी भूमि और व्यक्तिगत रिकॉर्ड से जुड़ी डिजिटल पहचान चाहते हैं।',
 'आधार, 7/12 और 8A भूमि रिकॉर्ड, बैंक खाता विवरण, मोबाइल नंबर।',
 'यह आईडी सरकार आधार, भूमि रिकॉर्ड और बैंक विवरण जोड़कर बनाती है। किसान इसे एग्रीस्टैक पोर्टल पर या अभियान चलने पर स्थानीय कृषि कार्यालय में जांच या सत्यापित कर सकते हैं।',
 'एग्रीस्टैक पर ऑनलाइन, या जब जिले में कार्यक्रम चालू हो तब तालुका कृषि कार्यालय के माध्यम से।'
FROM schemes WHERE scheme_key = 'farmerid';

INSERT IGNORE INTO scheme_text (scheme_id, lang, scheme_name, what_text, who_text, docs_text, steps_text, where_text)
SELECT id, 'mr', 'शेतकरी आयडी (ॲग्रीस्टॅक)',
 'शेतकरी आयडी ही शेतकऱ्याची एक विशिष्ट डिजिटल ओळख आहे, जी जमीन व वैयक्तिक नोंदी जोडून तयार केली जाते, जेणेकरून योजना लाभ मिळणे सोपे होते.',
 'आपल्या जमीन व वैयक्तिक नोंदींशी जोडलेली डिजिटल ओळख हवी असलेले शेतकरी.',
 'आधार, 7/12 आणि 8A जमीन नोंदी, बँक खाते तपशील, मोबाइल क्रमांक.',
 'ही आयडी सरकार आधार, जमीन नोंदी व बँक तपशील जोडून तयार करते. शेतकरी ती ॲग्रीस्टॅक पोर्टलवर किंवा मोहीम सुरू असताना स्थानिक कृषी कार्यालयात तपासू किंवा पडताळू शकतात.',
 'ॲग्रीस्टॅकवर ऑनलाइन, किंवा जिल्ह्यात कार्यक्रम सुरू असताना तालुका कृषी कार्यालयामार्फत.'
FROM schemes WHERE scheme_key = 'farmerid';

-- ---------------------------------------------------------------------
-- 5. DISTRICT OFFICES (STEP 3) - the "Find my district office" tile looks
--    the farmer's district up here. These rows are generic SAMPLE entries
--    (one per district) - the UI marks them SAMPLE and asks the farmer to
--    verify the exact address and phone number locally. Generated from the
--    districts table so it stays in step with it. Re-runnable (unique key).
-- ---------------------------------------------------------------------
INSERT IGNORE INTO district_offices (district_code, office_name, address, note)
SELECT code,
       'District Agriculture Office',
       CONCAT(name_en, ' district, Maharashtra (ask at the Taluka Agriculture Office or the Collector Office complex)'),
       'SAMPLE - verify the exact address and phone number locally'
FROM districts;

-- ---------------------------------------------------------------------
-- 6. WEATHER (STEP 4)
--    The pages NEVER call the internet: they only read weather_cache and
--    weather_forecast. A @Scheduled job fills those tables from
--    greenalerts.in (Jsoup) with Open-Meteo as a fallback.
--    Everything below is SAMPLE data so the demo also works offline; the UI
--    marks each seeded row "SAMPLE" (is_sample = 1 / source = 'SAMPLE').
-- ---------------------------------------------------------------------

-- 6a. weather_config - the URLs the 3-hourly job watches. One row per
--     location. These are REAL block pages (verified live) so the scraper
--     has something real to read when the college wifi allows it.
--     Re-runnable: a row is only added when its URL is not already there.
INSERT INTO weather_config (state, district, taluka, scrape_url, source)
SELECT t.state, t.district, t.taluka, t.url, 'GREENALERTS'
FROM (
    SELECT 'Maharashtra' AS state, 'Raigad'   AS district, 'Panvel'        AS taluka,
           'https://www.greenalerts.in/weather-forecast-india/maharashtra/raigad/panvel/' AS url
    UNION ALL SELECT 'Maharashtra', 'Pune',     'Haveli',
                    'https://www.greenalerts.in/weather-forecast-india/maharashtra/pune/haveli/'
    UNION ALL SELECT 'Maharashtra', 'Pune',     'Baramati',
                    'https://www.greenalerts.in/weather-forecast-india/maharashtra/pune/baramati/'
    UNION ALL SELECT 'Maharashtra', 'Nashik',   'Igatpuri',
                    'https://www.greenalerts.in/weather-forecast-india/maharashtra/nashik/igatpuri/'
    UNION ALL SELECT 'Maharashtra', 'Nagpur',   'Kamptee',
                    'https://www.greenalerts.in/weather-forecast-india/maharashtra/nagpur/kamptee/'
    UNION ALL SELECT 'Maharashtra', 'Kolhapur', 'Hatkanangale',
                    'https://www.greenalerts.in/weather-forecast-india/maharashtra/kolhapur/hatkanangale/'
) t
LEFT JOIN weather_config w ON w.scrape_url = t.url
WHERE w.id IS NULL;

-- 6b. SAMPLE current weather for ALL 36 districts, so /nowcast, /forecast and
--     the /map colour layer all work with the network unplugged.
--     The values come from each district's approximate latitude/longitude
--     (cooler to the north, more humid towards the coast) - they are only a
--     plausible pattern, never a real observation, and are labelled SAMPLE.
--     Re-runnable: one SAMPLE row per district, skipped when already present.
INSERT INTO weather_cache
    (config_id, timestamp, temperature, rain_chance, humidity, wind,
     daily_forecast, source, district, taluka, state, summary, is_sample)
SELECT NULL, NOW(),
       CONCAT(GREATEST(18, LEAST(38, ROUND(34 - (d.lat - 16) * 1.6))), ' °C'),
       '0 mm',
       CONCAT(GREATEST(30, LEAST(92, ROUND(88 - (d.lon - 72.8) * 7.5))), ' %'),
       '6 km/h',
       NULL, 'SAMPLE', d.name_en, NULL, 'Maharashtra',
       'SAMPLE reading for the offline demo', 1
FROM districts d
WHERE NOT EXISTS (
    SELECT 1 FROM weather_cache c
    WHERE c.district = d.name_en AND c.is_sample = 1
);

-- 6c. SAMPLE 7-day forecast for the six watched locations (the ones the
--     scraper knows a real block page for). Written for today + the next six
--     days. The scheduler overwrites a row in place once the real forecast
--     for that day arrives (ON DUPLICATE KEY UPDATE on the unique key
--     district + taluka + day), so a sample row never blocks a real one.
INSERT IGNORE INTO weather_forecast
    (district, taluka, forecast_day, temp_min, temp_max, rain_chance,
     humidity, wind, summary, source, updated_at)
WITH RECURSIVE d7(n) AS (
    SELECT 0 UNION ALL SELECT n + 1 FROM d7 WHERE n < 6
)
SELECT loc.d, loc.t, DATE_ADD(CURDATE(), INTERVAL d7.n DAY),
       CONCAT(23 + MOD(CRC32(CONCAT(loc.d, d7.n, 'min')), 4), ' °C'),
       CONCAT(32 + MOD(CRC32(CONCAT(loc.d, d7.n, 'max')), 5), ' °C'),
       CONCAT(MOD(CRC32(CONCAT(loc.t, d7.n, 'rain')), 60), ' %'),
       CONCAT(65 + MOD(CRC32(CONCAT(loc.d, d7.n, 'hum')), 20), ' %'),
       CONCAT(5 + MOD(CRC32(CONCAT(loc.t, d7.n, 'wind')), 9), ' km/h'),
       'SAMPLE forecast row - not a real forecast', 'SAMPLE', NOW()
FROM (
    SELECT 'Raigad'   AS d, 'Panvel'        AS t
    UNION ALL SELECT 'Pune',     'Haveli'
    UNION ALL SELECT 'Pune',     'Baramati'
    UNION ALL SELECT 'Nashik',   'Igatpuri'
    UNION ALL SELECT 'Nagpur',   'Kamptee'
    UNION ALL SELECT 'Kolhapur', 'Hatkanangale'
) loc
CROSS JOIN d7;

-- =====================================================================
-- 7. ALERTS - the rows store MESSAGE KEYS, never sentences.
--    The template renders each alert with #messages.msg(title) so the SAME row
--    reads correctly in English, Hindi and Marathi. The very first seed of this
--    table (sample_data.sql) still wrote plain English sentences, which the
--    page showed as a missing key. Any title that is not a key (a key always
--    contains a dot and has no spaces) is re-pointed at the seeded demo notice.
--    Re-runnable: once a row holds a key the WHERE no longer matches it.
-- =====================================================================
UPDATE alerts
   SET title   = 'alert.general.demo.title',
       message = 'alert.general.demo.message'
 WHERE title NOT LIKE '%.%';

-- =====================================================================
-- 8. DEMO ACCOUNTS ALREADY VERIFIED (PART B)
--    Login of a FARMER is blocked until the phone is verified, so the six
--    demo accounts must carry a verified phone (and email) or the demo would
--    stop working. The emails/phones below match the values already seeded in
--    farmer_profiles / labs - they are DEMO contact data, not invented people.
--    Re-runnable AND non-destructive: a WHERE ... email IS NULL means a real
--    registration made later is never overwritten by this script.
-- =====================================================================
UPDATE users SET email = 'farmer1@example.com', phone = '9876543210',
                 phone_verified = 1, email_verified = 1
 WHERE username = 'farmer1' AND email IS NULL;
UPDATE users SET email = 'farmer2@example.com', phone = '9876543211',
                 phone_verified = 1, email_verified = 1
 WHERE username = 'farmer2' AND email IS NULL;
UPDATE users SET email = 'farmer3@example.com', phone = '9876543212',
                 phone_verified = 1, email_verified = 1
 WHERE username = 'farmer3' AND email IS NULL;
UPDATE users SET email = 'lab1@example.com', phone = '022-1234567',
                 phone_verified = 1, email_verified = 1
 WHERE username = 'lab1' AND email IS NULL;
UPDATE users SET email = 'lab2@example.com', phone = '020-7654321',
                 phone_verified = 1, email_verified = 1
 WHERE username = 'lab2' AND email IS NULL;
UPDATE users SET email = 'owner@example.com', phone = '9876543299',
                 phone_verified = 1, email_verified = 1
 WHERE username = 'owner' AND email IS NULL;

-- =====================================================================
-- 9. DISTRICT CROPS (PART C) - the REFERENCE half of the crop map.
--    IMPORTANT: these are SAMPLE values, not an official dataset. No open
--    district-wise crop-area dataset could be fetched (data.gov.in serves
--    its data through an API key), so each district is given its commonly
--    reported MAJORITY crop with a SAMPLE share. `is_sample=1` makes the
--    crop map print "Sample data. Verify before use." on screen.
--    Re-runnable: INSERT IGNORE on the unique key (district_code, crop).
-- =====================================================================
INSERT IGNORE INTO district_crops (district_code, crop, share_percent, source, year, is_sample) VALUES
  ('ahmednagar','sugarcane',38.0,'SAMPLE - not an official dataset',2024,1),
  ('akola','cotton',45.0,'SAMPLE - not an official dataset',2024,1),
  ('amravati','cotton',40.0,'SAMPLE - not an official dataset',2024,1),
  ('aurangabad','cotton',35.0,'SAMPLE - not an official dataset',2024,1),
  ('beed','jowar',32.0,'SAMPLE - not an official dataset',2024,1),
  ('bhandara','rice',42.0,'SAMPLE - not an official dataset',2024,1),
  ('buldhana','cotton',36.0,'SAMPLE - not an official dataset',2024,1),
  ('chandrapur','rice',38.0,'SAMPLE - not an official dataset',2024,1),
  ('dhule','cotton',34.0,'SAMPLE - not an official dataset',2024,1),
  ('gadchiroli','rice',48.0,'SAMPLE - not an official dataset',2024,1),
  ('gondia','rice',44.0,'SAMPLE - not an official dataset',2024,1),
  ('hingoli','soybean',37.0,'SAMPLE - not an official dataset',2024,1),
  ('jalgaon','cotton',41.0,'SAMPLE - not an official dataset',2024,1),
  ('jalna','cotton',39.0,'SAMPLE - not an official dataset',2024,1),
  ('kolhapur','sugarcane',46.0,'SAMPLE - not an official dataset',2024,1),
  ('latur','soybean',35.0,'SAMPLE - not an official dataset',2024,1),
  ('mumbai-city','other',60.0,'SAMPLE - not an official dataset',2024,1),
  ('mumbai-suburban','rice',30.0,'SAMPLE - not an official dataset',2024,1),
  ('nagpur','cotton',33.0,'SAMPLE - not an official dataset',2024,1),
  ('nanded','soybean',40.0,'SAMPLE - not an official dataset',2024,1),
  ('nandurbar','cotton',37.0,'SAMPLE - not an official dataset',2024,1),
  ('nashik','onion',42.0,'SAMPLE - not an official dataset',2024,1),
  ('osmanabad','soybean',38.0,'SAMPLE - not an official dataset',2024,1),
  ('palghar','rice',36.0,'SAMPLE - not an official dataset',2024,1),
  ('parbhani','cotton',34.0,'SAMPLE - not an official dataset',2024,1),
  ('pune','sugarcane',29.0,'SAMPLE - not an official dataset',2024,1),
  ('raigad','rice',40.0,'SAMPLE - not an official dataset',2024,1),
  ('ratnagiri','mango',45.0,'SAMPLE - not an official dataset',2024,1),
  ('sangli','sugarcane',44.0,'SAMPLE - not an official dataset',2024,1),
  ('satara','sugarcane',35.0,'SAMPLE - not an official dataset',2024,1),
  ('sindhudurg','mango',47.0,'SAMPLE - not an official dataset',2024,1),
  ('solapur','sugarcane',31.0,'SAMPLE - not an official dataset',2024,1),
  ('thane','rice',34.0,'SAMPLE - not an official dataset',2024,1),
  ('wardha','cotton',42.0,'SAMPLE - not an official dataset',2024,1),
  ('washim','soybean',39.0,'SAMPLE - not an official dataset',2024,1),
  ('yavatmal','cotton',43.0,'SAMPLE - not an official dataset',2024,1);

SELECT 'data_new.sql applied' AS status;
