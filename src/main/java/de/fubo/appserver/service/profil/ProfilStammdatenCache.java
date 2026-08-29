package de.fubo.appserver.service.profil;

import de.fubo.appserver.common.config.CacheConfig;
import de.fubo.appserver.domain.profil.Profileintrag;
import de.fubo.appserver.repository.profil.SpielerRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Zwischenspeicher fuer die Profilstammdaten der Adminuebersicht (S3, Abschnitt 2 - Vorgabe
 * des Haupt-Entwicklers vom 29.08.2026: "Die Ergebnisse sollen fuer weitere Aufrufe
 * zwischengespeichert werden, da der Datenabruf vergleichsweise teuer ist").
 *
 * <h2>Warum das eine eigene Bean ist und keine Methode im Service</h2>
 * {@code @Cacheable} wirkt ueber einen Proxy. Ruft eine Methode derselben Klasse eine
 * annotierte Methode auf, laeuft der Aufruf am Proxy vorbei und der Zwischenspeicher bleibt
 * wirkungslos - <b>ohne Fehlermeldung</b>. Genau das passierte, stuende
 * {@code @Cacheable} an einer Methode, die {@code SpielerVerwaltungService#uebersicht()}
 * selbst aufruft. Eine eigene Bean erzwingt den Weg ueber den Proxy.
 *
 * <h2>Was hier <i>nicht</i> drin liegt</h2>
 * Der Belegtstatus. Er wird aus den aktiven Sitzungen abgeleitet (A6) und aendert sich, ohne
 * dass jemand ein Profil anfasst; in einem Speicher, der nur bei Profilaenderungen verworfen
 * wird, bliebe er beliebig lange falsch. Er wird deshalb bei jedem Aufruf frisch geholt und
 * erst im Service dazugelegt. Ausfuehrlich am Record {@link Profileintrag}.
 *
 * <h2>Reichweite des Speichers</h2>
 * Anwendungsweit, nicht je Sitzung - entschieden am 29.08.2026. Es gibt genau einen Admin
 * ({@code ck_admin_konto_singleton}), ein sitzungsbezogener Speicher waere also derselbe
 * Speicher mit mehr Maschinerie: einem eigenen Schluessel je Sitzung und dem Aufraeumen beim
 * Abmelden. Tragend ist ohnehin nicht die Reichweite, sondern das <b>Verwerfen beim
 * Schreiben</b>; ohne das zeigte das Bearbeitungsformular nach dem Speichern die alten Werte.
 */
@Component
public class ProfilStammdatenCache {

    private final SpielerRepository spielerRepository;

    public ProfilStammdatenCache(SpielerRepository spielerRepository) {
        this.spielerRepository = spielerRepository;
    }

    /**
     * Liefert alle Profile mit ihren Skillwerten - beim ersten Aufruf aus der Datenbank,
     * danach aus dem Zwischenspeicher.
     *
     * <p><b>Ein einziger Eintrag, ohne Schluessel.</b> Die Abfrage nimmt keine Parameter
     * entgegen; Spring verwendet dann {@code SimpleKey.EMPTY}, und der Speicher enthaelt
     * genau eine Liste.
     *
     * <p>{@code readOnly = true}: Die Transaktion ist noetig, damit die Verbindung sauber
     * begrenzt ist, geschrieben wird hier nichts.
     *
     * <p>Die zurueckgegebene Liste ist unveraenderlich ({@code toList()}), und die Skillkarten
     * darin sind es auch ({@code Profileintrag}). Das ist kein Beiwerk: Jeder Aufrufer
     * bekommt <i>dieselbe</i> Instanz aus dem Speicher, und eine veraenderliche Liste liesse
     * sich von einem Aufrufer fuer alle folgenden umschreiben.
     *
     * @return alle Profile, Spielerprofile zuerst, das technische Adminkonto zuletzt
     */
    @Cacheable(CacheConfig.PROFILSTAMMDATEN)
    @Transactional(readOnly = true)
    public List<Profileintrag> alle() {
        return List.copyOf(spielerRepository.findeProfilstammdaten());
    }

    /**
     * Verwirft den Zwischenspeicher.
     *
     * <p><b>Von jedem Vorgang aufzurufen, der ein Profil oder einen Skillwert aendert</b> -
     * anlegen, entfernen, blockieren, bearbeiten und das Umbenennen des Adminprofils. Wird
     * eine dieser Stellen vergessen, liefert die Uebersicht veraltete Daten, und zwar
     * unbegrenzt lange: Es gibt keine Frist, die den Fehler von selbst heilte.
     *
     * <p>{@code allEntries = true}, obwohl es nur einen Eintrag gibt - so bleibt der Aufruf
     * richtig, falls die Abfrage spaeter Parameter bekommt.
     *
     * <p><b>Zum Zusammenspiel mit der Transaktion:</b> Das Verwerfen geschieht beim Aufruf,
     * der Commit erst danach. Ein Leser, der genau dazwischen faellt, koennte den Speicher
     * erneut mit dem alten Stand fuellen. Bei einem einzigen Admin ist dieses Fenster
     * theoretisch; und die Richtung stimmt: Ein zu <i>frueh</i> geleerter Speicher kostet eine
     * Abfrage, ein zu spaet geleerter zeigte falsche Werte. Deshalb steht der Aufruf am Ende
     * der schreibenden Methode und nicht als {@code @CacheEvict} an ihr - so bleibt sichtbar,
     * dass er zum Vorgang gehoert.
     */
    @CacheEvict(value = CacheConfig.PROFILSTAMMDATEN, allEntries = true)
    public void verwerfen() {
        // Die Wirkung steckt vollstaendig in der Annotation.
    }
}
