package de.fubo.appserver.service.auth;

import de.fubo.appserver.domain.auth.Zugangsdaten;
import de.fubo.appserver.repository.auth.ZugangsdatenRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * Prueft und aendert die zentrale PIN (A3).
 *
 * <p><b>Warum BCrypt und nicht SHA-256 wie beim Session-Token?</b> Die PIN hat geringe
 * Entropie und ist damit einem Woerterbuch- oder Vollstaendigkeitsangriff zugaenglich.
 * BCrypt ist absichtlich langsam und macht genau das teuer. Beim Session-Token ist es
 * umgekehrt: volle Entropie, dafuer ein Hash bei jedem Request - dort zaehlt Tempo.
 */
@Service
public class PinService {

    /** Die Tabelle ist per CHECK-Constraint auf genau diese Zeile beschraenkt. */
    static final short ZUGANGSDATEN_ID = 1;

    private final ZugangsdatenRepository zugangsdatenRepository;
    private final PasswordEncoder passwortEncoder;

    public PinService(ZugangsdatenRepository zugangsdatenRepository, PasswordEncoder passwortEncoder) {
        this.zugangsdatenRepository = zugangsdatenRepository;
        this.passwortEncoder = passwortEncoder;
    }

    /**
     * Vergleicht die eingegebene PIN mit dem hinterlegten Hash.
     *
     * <p>Der Vergleich laeuft ueber {@link PasswordEncoder#matches}, nicht ueber einen
     * Zeichenkettenvergleich der Hashes: BCrypt bettet Kostenfaktor und Salt in den Hash
     * ein, ein erneutes Kodieren derselben PIN liefert deshalb nie denselben Wert.
     *
     * @param pin Klartext-Eingabe aus dem Anfragekoerper
     * @return {@code true}, wenn die PIN stimmt
     * @throws IllegalStateException wenn noch keine PIN hinterlegt ist. Das ist ein
     *                               Betriebsfehler (Bootstrap nicht gelaufen) und kein
     *                               fachlicher Fehlerfall - deshalb 500 und kein 401.
     */
    @Transactional(readOnly = true)
    public boolean stimmt(String pin) {
        if (pin == null || pin.isBlank()) {
            return false;
        }
        Zugangsdaten zugangsdaten = zugangsdatenRepository.findById(ZUGANGSDATEN_ID)
                .orElseThrow(() -> new IllegalStateException(
                        "profil.zugangsdaten enthaelt keine Zeile - der Start-Bootstrap ist nicht gelaufen."));

        return passwortEncoder.matches(pin, zugangsdaten.getPinHash());
    }

    /**
     * Setzt eine neue zentrale PIN. Wird vom Bootstrap und spaeter vom Admin-Endpunkt (S3)
     * genutzt.
     *
     * <p>Der Aufrufer ist dafuer zustaendig, anschliessend alle offenen Sitzungen zu
     * widerrufen ({@code SessionService#alleWiderrufen}) - sonst blieben Nutzer angemeldet,
     * die nur die alte PIN kannten.
     *
     * @param neuePin      Klartext der neuen PIN
     * @param adminKontoId Id des aendernden Admin-Kontos oder {@code null} beim Bootstrap
     */
    @Transactional
    public void setzen(String neuePin, Short adminKontoId) {
        Zugangsdaten zugangsdaten = zugangsdatenRepository.findById(ZUGANGSDATEN_ID)
                .orElseGet(() -> {
                    Zugangsdaten neu = new Zugangsdaten();
                    neu.setId(ZUGANGSDATEN_ID);
                    return neu;
                });

        zugangsdaten.setPinHash(passwortEncoder.encode(neuePin));
        zugangsdaten.setGeaendertAm(OffsetDateTime.now());
        zugangsdaten.setGeaendertVon(adminKontoId);

        zugangsdatenRepository.save(zugangsdaten);
    }

    /** Meldet, ob bereits eine zentrale PIN hinterlegt ist (Bootstrap-Entscheidung). */
    @Transactional(readOnly = true)
    public boolean istHinterlegt() {
        return zugangsdatenRepository.existsById(ZUGANGSDATEN_ID);
    }
}
