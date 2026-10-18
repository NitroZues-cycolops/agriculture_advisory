-- Users (password is 'password123' hashed with BCrypt)
INSERT INTO users (username, password, role, enabled) VALUES 
('farmer1', '$2a$10$8.UnVuG9HHgffUDAlk8qn.6ndf00Jie3vlNYFV7oazke1qEqS4GZ.', 'FARMER', TRUE),
('farmer2', '$2a$10$8.UnVuG9HHgffUDAlk8qn.6ndf00Jie3vlNYFV7oazke1qEqS4GZ.', 'FARMER', TRUE),
('farmer3', '$2a$10$8.UnVuG9HHgffUDAlk8qn.6ndf00Jie3vlNYFV7oazke1qEqS4GZ.', 'FARMER', TRUE),
('lab1', '$2a$10$8.UnVuG9HHgffUDAlk8qn.6ndf00Jie3vlNYFV7oazke1qEqS4GZ.', 'LAB', TRUE),
('lab2', '$2a$10$8.UnVuG9HHgffUDAlk8qn.6ndf00Jie3vlNYFV7oazke1qEqS4GZ.', 'LAB', TRUE),
('owner', '$2a$10$8.UnVuG9HHgffUDAlk8qn.6ndf00Jie3vlNYFV7oazke1qEqS4GZ.', 'OWNER', TRUE);

-- Farmer Profiles
INSERT INTO farmer_profiles (user_id, full_name, phone, district, taluka, crops, farm_area, unit, soil_type, description) VALUES 
(1, 'Rahul Patil', '9876543210', 'Raigad', 'Panvel', 'Rice, Sugarcane', 5.0, 'acre', 'Black', 'Rice and sugarcane farmer'),
(2, 'Anita Shinde', '9876543211', 'Pune', 'Haveli', 'Wheat, Onion', 2.5, 'hectare', 'Red', 'Small scale vegetable farmer'),
(3, 'Suresh Deshmukh', '9876543212', 'Nashik', 'Niphad', 'Grapes, Pomegranate', 10.0, 'acre', 'Alluvial', 'Orchard owner');

-- Weather Config
INSERT INTO weather_config (state, district, taluka, scrape_url) VALUES 
('Maharashtra', 'Raigad', 'Panvel', 'https://www.greenalerts.in/weather-forecast-india/maharashtra/raigad/panvel/'),
('Maharashtra', 'Pune', 'Haveli', 'https://www.greenalerts.in/weather-forecast-india/maharashtra/pune/haveli/'),
('Maharashtra', 'Nashik', 'Niphad', 'https://www.greenalerts.in/weather-forecast-india/maharashtra/nashik/niphad/');

-- Sample Weather Cache
INSERT INTO weather_cache (config_id, timestamp, temperature, rain_chance, humidity, wind, daily_forecast) VALUES 
(1, NOW(), '28°C', '10%', '65%', '12 km/h', 'Sunny intervals throughout the day'),
(2, NOW(), '25°C', '0%', '55%', '8 km/h', 'Clear skies'),
(3, NOW(), '30°C', '5%', '45%', '15 km/h', 'Hot and dry');

-- Labs
INSERT INTO labs (user_id, name, address, phone, timings, sample_types, requirements, district, lat, lon) VALUES 
(4, 'Raigad Soil Testing Lab', 'Main Road, Panvel, Raigad', '022-1234567', '9 AM - 6 PM', 'Soil, Water', 'Bring 500g dry soil', 'Raigad', 18.989, 73.117),
(5, 'Pune Agri Research Center', 'Shivaji Nagar, Pune', '020-7654321', '10 AM - 5 PM', 'Seed, Fertilizer', 'Provide 1kg sample', 'Pune', 18.520, 73.856);

-- Lab Requests
INSERT INTO lab_requests (farmer_id, lab_id, sample_type, crop, notes, status, tracking_id) VALUES 
(1, 1, 'Soil', 'Rice', 'Need testing for next season', 'REQUEST_SUBMITTED', 'TRK-1001'),
(2, 2, 'Seed', 'Wheat', 'Check germination quality', 'SAMPLE_RECEIVED', 'TRK-1002'),
(3, 2, 'Fertilizer', 'Grapes', 'Analyze nutrient content', 'REPORT_READY', 'TRK-1003');

-- Schemes
INSERT INTO schemes (name_key, description_key, eligibility_key, documents_key, contact_info_key, official_link) VALUES 
('scheme.pmkisan.name', 'scheme.pmkisan.desc', 'scheme.pmkisan.elig', 'scheme.pmkisan.docs', 'scheme.pmkisan.contact', 'https://pmkisan.gov.in'),
('scheme.mahadbt.name', 'scheme.mahadbt.desc', 'scheme.mahadbt.elig', 'scheme.mahadbt.docs', 'scheme.mahadbt.contact', 'https://mahadbt.maharashtra.gov.in'),
('scheme.pmfby.name', 'scheme.pmfby.desc', 'scheme.pmfby.elig', 'scheme.pmfby.docs', 'scheme.pmfby.contact', 'https://pmfby.gov.in');

-- Alerts
INSERT INTO alerts (user_id, title, message, type, severity) VALUES 
(1, 'Heavy Rain Warning', 'Heavy rain expected in Panvel for next 2 days. Postpone spraying.', 'PERSONALIZED', 'SEVERE'),
(1, 'General Update', 'New government schemes for rice farmers announced.', 'GENERAL', 'SUCCESS');
