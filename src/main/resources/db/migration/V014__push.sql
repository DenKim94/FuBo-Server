-- A25, S8: Web-Push-Benachrichtigungen. Vier Aenderungen, eine Migration - sie gehoeren zu
-- EINER Funktion, und DATENMODELL.md (Aenderungsprotokoll 19 bis 22) legt sie deshalb
-- zusammen. Reihenfolge: erst die neue Tabelle, dann die drei ALTER.
--
-- Push ist ein rein AUSGEHENDER Weg: Der Server spricht die Push-Dienste der
-- Browserhersteller an (RFC 8030), es entsteht kein neuer eingehender Endpunkt fuer den
-- Versand. Die Geheimnisse dieses Wegs - das VAPID-Schluesselpaar - stehen bewusst NICHT
-- hier, sondern in Umgebungsvariablen: configs.app_config wird ueber einen Admin-Endpunkt
-- gelesen und geschrieben, ein privater Schluessel haette darin nichts zu suchen.

-- ---------------------------------------------------------------------------------------
-- 1. profil.push_abo - ein Abonnement je Spieler UND Geraet (A25c)
-- ---------------------------------------------------------------------------------------
--
-- Eigene Tabelle statt Spalten an spieler: Ein Spieler abonniert auf mehreren Geraeten.
-- Im Schema profil, weil ein Abonnement zur Person gehoert und nicht zum Spieltag.
--
-- Gaeste sind ausgeschlossen (A25d) - und zwar ohne eigene Bedingung: Eine Gastsitzung hat
-- keine Zeile in profil.spieler, der NOT NULL-Fremdschluessel erledigt das von selbst. Ein
-- Abonnement ueberdauerte die Gastsitzung ohnehin und liesse sich danach keiner Person mehr
-- zuordnen.
CREATE TABLE profil.push_abo (
                                 id                 BIGSERIAL    PRIMARY KEY,
                                 spieler_id         BIGINT       NOT NULL,
                                 endpoint           TEXT         NOT NULL,
                                 endpoint_hash      CHAR(64)     NOT NULL,
                                 p256dh             VARCHAR(120) NOT NULL,
                                 auth               VARCHAR(32)  NOT NULL,
                                 geraet_bezeichnung VARCHAR(80),
                                 erstellt_am        TIMESTAMPTZ  NOT NULL DEFAULT now(),
                                 letzter_versand_am TIMESTAMPTZ,
                                 fehlversuche       SMALLINT     NOT NULL DEFAULT 0,
                                 deaktiviert_am     TIMESTAMPTZ,
                                 version            BIGINT       NOT NULL DEFAULT 0,
                                 CONSTRAINT fk_push_abo_spieler FOREIGN KEY (spieler_id)
                                     REFERENCES profil.spieler (id) ON DELETE CASCADE,
                                 CONSTRAINT uq_push_abo_endpoint_hash UNIQUE (endpoint_hash),
                                 CONSTRAINT ck_push_abo_fehlversuche CHECK (fehlversuche >= 0)
);

-- endpoint_hash statt UNIQUE (endpoint): Die Laenge der Endpoint-Adresse ist in RFC 8030
-- NICHT begrenzt, ein btree-Index fasst rund 2 700 Byte je Eintrag. Ein Unique-Index direkt
-- auf der Adresse koennte also erst zur Laufzeit fehlschlagen - bei einem Anbieter, den
-- niemand getestet hat. Der SHA-256-Hex hat feste Laenge 64; dasselbe Verfahren wie beim
-- Session-Token aus V003.
--
-- Der Unique-Constraint gilt GLOBAL, nicht je Spieler. Die Adresse identifiziert eine
-- Browserinstallation, keine Person: Meldet sich auf einem geteilten Geraet ein anderer
-- Spieler an, muss das Abonnement die Person wechseln - sonst empfaengt der vorherige
-- weiter. Umgesetzt ueber INSERT ... ON CONFLICT (endpoint_hash) DO UPDATE, womit das
-- Anlegen zugleich idempotent ist.

-- deaktiviert_am statt sofortigem Loeschen: Der Zwischenzustand unterscheidet "nie
-- abonniert" von "Abonnement erloschen" und traegt den Zaehler fehlversuche. Er ist KEINE
-- Warteschlange - eine einzelne Nachricht wird nie wiederholt. fehlversuche misst die
-- Gesundheit des Abonnements ueber mehrere Anlaesse hinweg: Fuenf Fehlschlaege heissen
-- "diese Adresse ist tot", nicht "diese Nachricht steht noch aus".
--
-- Der CHECK ist ein Riegel gegen einen Vorzeichenfehler, nicht gegen den geplanten Weg -
-- derselbe Gedanke wie bei ck_spieler_bilanz aus V011.

-- Partieller Index: Der Versand fragt ausschliesslich die AKTIVEN Abonnements eines
-- Spielers ab. Dieselbe Form wie ix_session_aktiv aus V003.
CREATE INDEX ix_push_abo_aktiv
    ON profil.push_abo (spieler_id)
    WHERE deaktiviert_am IS NULL;

-- ---------------------------------------------------------------------------------------
-- 2. profil.spieler.push_erwuenscht - der Personenschalter (A25f)
-- ---------------------------------------------------------------------------------------
--
-- Er gehoert an spieler und nicht an push_abo: A25f verlangt das Abschalten ohne Gang durch
-- jedes einzelne Geraet. Die geraeteweise Entscheidung heisst "Abonnement widerrufen" und
-- ist eine andere Handlung - deshalb loescht das Abschalten auch keine Abonnements, sonst
-- verlangte das Wiedereinschalten einen neuen Browserdialog.
--
-- DEFAULT true, und das ist kein Widerspruch zum Vorgabewert von hallen_modus_aktiv: Die
-- Zustimmung der Person liegt bereits im Browserdialog vor, ohne den kein Abonnement
-- existiert. true heisst hier "nicht widersprochen" und kann fuer sich genommen nichts
-- ausloesen; false verlangte zwei Zustimmungen fuer dieselbe Sache.
--
-- Der Name lautet push_erwuenscht und nicht push_aktiv: configs.app_config.push_aktiv
-- traegt diesen Namen bereits und bedeutet etwas anderes - "sendet die Anlage?" gegenueber
-- "will diese Person empfangen?".
ALTER TABLE profil.spieler
    ADD COLUMN push_erwuenscht BOOLEAN NOT NULL DEFAULT true;

-- ---------------------------------------------------------------------------------------
-- 3. spieltag.termin.push_erinnerung_am - der Einmalversand der Erinnerung (A25b)
-- ---------------------------------------------------------------------------------------
--
-- Dieselbe Rolle und dasselbe Muster wie halle_abgesagt_am aus V012: Ein bedingter
-- UPDATE ... WHERE push_erinnerung_am IS NULL laesst genau einen Lauf durch. Markiert wird
-- VOR dem Versand - ein Absturz mitten im Versand kostet dann einzelne Nachrichten,
-- waehrend die umgekehrte Reihenfolge nach einem Neustart ALLE Empfaenger ein zweites Mal
-- benachrichtigte.
--
-- NULL heisst "noch nicht erinnert" und ist der Normalzustand jedes Termins - deshalb
-- nullbar und ohne Vorgabewert. Kein zusaetzlicher boolean: Der Zeitpunkt beantwortet beide
-- Fragen, zwei Spalten koennten auseinanderlaufen.
--
-- Kein Index: Die Abfrage des Erinnerungsauftrags grenzt ueber status und den Terminbeginn
-- ein; die Spalte ist dabei nur eine weitere Bedingung, kein Suchschluessel.
--
-- Anders als halle_abgesagt_am wird diese Spalte an der Termin-Entity GEMAPPT: Sie faellt
-- beim Verschieben eines Termins zurueck (Entscheidung vom 14.09.2026), und TerminService
-- arbeitet dort mit der geladenen Entity. Ein natives UPDATE daneben waere die verbotene
-- Kombination aus Versionsspalte und verwalteter Entity.
ALTER TABLE spieltag.termin
    ADD COLUMN push_erinnerung_am TIMESTAMPTZ;

-- ---------------------------------------------------------------------------------------
-- 4. configs.app_config - Hauptschalter und Vorlauf (A25e)
-- ---------------------------------------------------------------------------------------
--
-- push_aktiv NOT NULL DEFAULT true. Die naheliegende Analogie zu hallen_modus_aktiv (dort
-- false) traegt NICHT, und der Unterschied ist der Empfaenger: Die Hallenabsage geht an
-- einen Aussenstehenden, der nie zugestimmt hat - dort ist "aus, bis jemand es einschaltet"
-- die sichere Richtung. Eine Push-Nachricht erreicht ausschliesslich, wer im Browserdialog
-- zugestimmt hat, und true kann fuer sich genommen nichts ausloesen: ohne Abonnement und
-- ohne eingerichtete VAPID-Schluessel geht nichts hinaus. false erzeugte dagegen genau das
-- Fehlerbild, das A25 vermeiden will - alles eingerichtet, jemand hat zugestimmt, und es
-- kommt nichts an, mit einer Ursache, die in einem Adminformular steht, an das niemand
-- denkt. A25(e) verlangt die Voreinstellung "eingeschaltet" ausdruecklich.
--
-- push_erinnerung_stunden: Vorlauf der Erinnerung an eine offene Rueckmeldung, Vorgabe 24.
-- Der Wert gilt anwendungsweit und nicht je Termin; ein Feld am Termin waere ein weiteres
-- Pflichtfeld im Terminformular.
ALTER TABLE configs.app_config
    ADD COLUMN push_aktiv              BOOLEAN  NOT NULL DEFAULT true,
    ADD COLUMN push_erinnerung_stunden SMALLINT NOT NULL DEFAULT 24;

-- Die Datenbank verlangt nur "groesser als null". Die Obergrenze von 168 Stunden (eine
-- Woche) steht am DTO und nicht hier: Sie ist eine Eingabepruefung an der API-Grenze, und
-- ein CHECK braechte statt einer Meldung einen 500 mit einem Constraint-Namen im Log.
-- Ohne irgendeine Untergrenze waere ein Vorlauf von 0 gueltig, und die Erinnerung ginge nie
-- hinaus, weil das Zeitfenster (beginn > jetzt AND beginn <= jetzt + vorlauf) leer bliebe.
ALTER TABLE configs.app_config
    ADD CONSTRAINT ck_app_config_push_vorlauf CHECK (push_erinnerung_stunden > 0);
