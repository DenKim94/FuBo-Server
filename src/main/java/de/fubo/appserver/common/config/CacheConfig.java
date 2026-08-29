package de.fubo.appserver.common.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Aktiviert die Auswertung von {@code @Cacheable} und {@code @CacheEvict} und stellt den
 * Zwischenspeicher bereit (S3, Abschnitt 2 - Vorgabe des Haupt-Entwicklers vom 29.08.2026).
 *
 * <p>Wie {@code SchedulingConfig}: Ohne diese Klasse bleiben die Annotationen wirkungslos -
 * <b>und zwar ohne Fehlermeldung</b>. Der Aufruf ginge dann bei jedem Zugriff an die
 * Datenbank, und niemandem fiele es auf.
 *
 * <h2>Warum der Cache-Manager von Hand entsteht</h2>
 * Spring Boot brauchte dafuer {@code spring-boot-starter-cache} und richtete dann einen
 * Zwischenspeicher ein, der <b>jeden</b> angefragten Namen stillschweigend anlegt. Ein
 * Tippfehler in einem {@code @CacheEvict} traefe damit einen leeren, neuen Speicher statt des
 * gemeinten - der alte Eintrag bliebe stehen, und der Admin saehe nach dem Speichern seine
 * eigenen alten Werte. Der Konstruktor mit festen Namen schliesst das aus: Ein unbekannter
 * Name liefert {@code null} und fuehrt sofort zu einem Fehler statt zu einem stillen
 * Falschverhalten.
 *
 * <p>Der Weg ist derselbe wie bei {@code MailConfig}: Wo eine Autokonfiguration einen Fehler
 * still durchgehen liesse, baut das Projekt die Bean selbst. Eine zusaetzliche Abhaengigkeit
 * braucht es dafuer nicht - {@code @EnableCaching} und {@link ConcurrentMapCacheManager}
 * stehen beide in {@code spring-context}, das ohnehin im Klassenpfad liegt.
 *
 * <h2>Warum ein einfacher Speicher genuegt</h2>
 * Kein Verfallszeitpunkt, keine Groessengrenze, kein Caffeine. Der Inhalt ist <b>ein</b>
 * Eintrag - die Profilliste -, und er wird nicht nach Ablauf einer Frist ungueltig, sondern
 * genau dann, wenn ein Profil geaendert wird. Dafuer ist das ausdrueckliche Verwerfen der
 * richtige Weg und eine Frist der falsche: Eine Frist liesse den Admin nach dem Speichern
 * kurzzeitig seine alte Eingabe sehen, was in einem Bearbeitungsformular niemand versteht.
 *
 * <p>Der Speicher liegt im Arbeitsspeicher <b>einer</b> Instanz. Das passt zum Betrieb: eine
 * Anwendung auf einem Raspberry Pi hinter nginx. Bei mehreren Instanzen muesste er geteilt
 * oder abgeschaltet werden - dann waere er beim Schreiben nur lokal verworfen, und die andere
 * Instanz lieferte weiter alte Werte.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * Name des Zwischenspeichers fuer die Profilstammdaten.
     *
     * <p>Als Konstante und nicht als Zeichenkette an drei Stellen: {@code @Cacheable} und
     * {@code @CacheEvict} muessen denselben Namen tragen, sonst verwirft das Schreiben einen
     * anderen Speicher als den gefuellten.
     */
    public static final String PROFILSTAMMDATEN = "profilstammdaten";

    /**
     * Zwischenspeicher mit fest vorgegebenen Namen.
     *
     * <p>Der Konstruktor mit Namensliste schaltet {@code dynamic} ab: Nur die hier genannten
     * Speicher existieren.
     */
    @Bean
    public CacheManager cacheManager() {
        return new ConcurrentMapCacheManager(PROFILSTAMMDATEN);
    }
}
