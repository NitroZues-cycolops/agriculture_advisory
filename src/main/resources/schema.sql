CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(100) NOT NULL,
    role VARCHAR(20) NOT NULL,
    enabled BOOLEAN DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS farmer_profiles (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    full_name VARCHAR(100) NOT NULL,
    phone VARCHAR(15) NOT NULL,
    district VARCHAR(50) NOT NULL,
    taluka VARCHAR(50) NOT NULL,
    crops VARCHAR(255),
    farm_area DOUBLE,
    unit VARCHAR(10),
    soil_type VARCHAR(50),
    description TEXT,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS weather_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    state VARCHAR(50) NOT NULL,
    district VARCHAR(50) NOT NULL,
    taluka VARCHAR(50) NOT NULL,
    scrape_url VARCHAR(255) NOT NULL
);

CREATE TABLE IF NOT EXISTS weather_cache (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    config_id BIGINT NOT NULL,
    timestamp DATETIME NOT NULL,
    temperature VARCHAR(10),
    rain_chance VARCHAR(10),
    humidity VARCHAR(10),
    wind VARCHAR(20),
    daily_forecast TEXT, -- JSON or serialized data
    FOREIGN KEY (config_id) REFERENCES weather_config(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS labs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT,
    name VARCHAR(100) NOT NULL,
    address TEXT NOT NULL,
    phone VARCHAR(15) NOT NULL,
    timings VARCHAR(100),
    sample_types VARCHAR(255),
    requirements TEXT,
    district VARCHAR(50) NOT NULL,
    lat DOUBLE,
    lon DOUBLE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS lab_requests (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    farmer_id BIGINT NOT NULL,
    lab_id BIGINT NOT NULL,
    sample_type VARCHAR(50) NOT NULL,
    crop VARCHAR(50),
    notes TEXT,
    status VARCHAR(50) NOT NULL DEFAULT 'REQUEST_SUBMITTED',
    tracking_id VARCHAR(50) NOT NULL UNIQUE,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (farmer_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (lab_id) REFERENCES labs(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS lab_results (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id BIGINT NOT NULL UNIQUE,
    ph DOUBLE,
    nitrogen DOUBLE,
    phosphorus DOUBLE,
    potassium DOUBLE,
    organic_carbon DOUBLE,
    electrical_conductivity DOUBLE,
    remarks TEXT,
    pdf_path VARCHAR(255),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (request_id) REFERENCES lab_requests(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS schemes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name_key VARCHAR(100) NOT NULL, -- Key for messages_xx.properties
    description_key VARCHAR(100) NOT NULL,
    eligibility_key VARCHAR(100) NOT NULL,
    documents_key VARCHAR(100) NOT NULL,
    contact_info_key VARCHAR(100) NOT NULL,
    official_link VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS community_posts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    content TEXT NOT NULL,
    image_path VARCHAR(255),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS community_comments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    post_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    content TEXT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (post_id) REFERENCES community_posts(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS community_votes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    post_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    vote_type VARCHAR(10) NOT NULL, -- 'UP', 'DOWN'
    UNIQUE(post_id, user_id),
    FOREIGN KEY (post_id) REFERENCES community_posts(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS direct_messages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sender_id BIGINT NOT NULL,
    receiver_id BIGINT NOT NULL,
    content TEXT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    is_read BOOLEAN DEFAULT FALSE,
    FOREIGN KEY (sender_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (receiver_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS alerts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(100) NOT NULL,
    message TEXT NOT NULL,
    type VARCHAR(20) NOT NULL, -- 'GENERAL', 'PERSONALIZED'
    severity VARCHAR(20) NOT NULL, -- 'SUCCESS', 'WARNING', 'SEVERE'
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
