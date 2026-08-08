INSERT INTO profil.skill_kategorie (schluessel, bezeichnung, gewicht, reihenfolge, min_wert, max_wert) VALUES
       ('ANGRIFF',      'Angriff',      1.00, 1, 0, 6),
       ('VERTEIDIGUNG', 'Verteidigung', 1.00, 2, 0, 6),
       ('SPIELSTAERKE', 'Spielstärke',  1.00, 3, 0, 6),
       ('LAUFSTAERKE',  'Laufstärke',   1.00, 4, 0, 6),
       ('TORWART',      'Torwart',      0.30, 5, 0, 3);

INSERT INTO profil.gast_vorlage (stufe, kategorie, wert) VALUES
         ('SCHWACH', 'ANGRIFF', 2),
         ('SCHWACH', 'VERTEIDIGUNG', 2),
         ('SCHWACH', 'SPIELSTAERKE', 2),
         ('SCHWACH', 'LAUFSTAERKE', 2),
         ('SCHWACH', 'TORWART', 0),
         ('MITTEL',  'ANGRIFF', 3),
         ('MITTEL',  'VERTEIDIGUNG', 3),
         ('MITTEL',  'SPIELSTAERKE', 3),
         ('MITTEL',  'LAUFSTAERKE', 3),
         ('MITTEL',  'TORWART', 0),
         ('STARK',   'ANGRIFF', 4),
         ('STARK',   'VERTEIDIGUNG', 4),
         ('STARK',   'SPIELSTAERKE', 4),
         ('STARK',   'LAUFSTAERKE', 4),
         ('STARK',   'TORWART', 0);

INSERT INTO profil.gast_slot (id, anzeige_name) VALUES
        (1, 'Gast 1'), (2, 'Gast 2'), (3, 'Gast 3'), (4, 'Gast 4');

INSERT INTO configs.app_config (id) VALUES (1);