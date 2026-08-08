CREATE SCHEMA IF NOT EXISTS profil;
CREATE SCHEMA IF NOT EXISTS spieltag;
CREATE SCHEMA IF NOT EXISTS configs;

COMMENT ON SCHEMA profil   IS 'Spielerprofile, Skills, Zugang und Sitzungen';
COMMENT ON SCHEMA spieltag IS 'Termine, Teilnehmer, Teamgenerierung, Ergebnisse';
COMMENT ON SCHEMA configs  IS 'Anwendungsspezifische Konfigurationsparameter';