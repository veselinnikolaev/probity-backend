INSERT INTO users (username, email, password, role)
VALUES ('admin', 'admin@email.com', '$2a$12$hCZUp2/h6SMiZwkRvPaNCut/AQZaQF0CZiPEg68RuGTDDQW/zjPFW', 'ADMIN')
ON CONFLICT DO NOTHING;