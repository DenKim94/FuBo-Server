package de.fubo.appserver.controller.admin;

import de.fubo.appserver.common.config.ApiVersionConfig;
import de.fubo.appserver.dto.admin.SkillKategorieInfo;
import de.fubo.appserver.repository.profil.SkillKategorieRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Liefert die Skillkategorien samt Wertebereich an das Adminformular (S3, Abschnitt 4).
 *
 * <p><b>Zum Pfad:</b> {@code GET /api/{version}/admin/skills/lesen}. Die Filterchain verlangt
 * fuer alles unterhalb von {@code /api/*&#47;admin/**} die Rolle {@code ADMIN}; eine eigene
 * Regel je Endpunkt braucht es nicht.
 *
 * <p><b>Warum der Endpunkt Adminrechte verlangt und nicht offen ist:</b> Er verraet den
 * Wertebereich und damit den Aufbau der Bewertung. Ein normaler Nutzer sieht nie Skills (A12);
 * ihm den Rahmen dieser Skills zu zeigen waere die halbe Auskunft. Sollte einmal eine
 * Gast-Selbsteinschaetzung mit Kategorienamen entstehen, ist das ein eigener, bewusst
 * schmalerer Endpunkt - kein Aufweichen dieses hier.
 *
 * <p><b>Ohne Service dazwischen, mit Absicht.</b> Es gibt nichts zu entscheiden: keine
 * Transaktionsgrenze, keine Pruefung, kein Protokolleintrag - nur eine Abfrage und die
 * Abbildung auf das DTO. Eine Serviceklasse, die einen Repository-Aufruf durchreicht, ist
 * eine Schicht ohne Inhalt. Sobald hier eine Entscheidung hinzukommt, bekommt sie einen
 * Service; heute waere er Ballast.
 */
@RestController
@RequestMapping(ApiVersionConfig.API_PRAEFIX + "/admin")
public class SkillKategorieController {

    private final SkillKategorieRepository skillKategorieRepository;

    public SkillKategorieController(SkillKategorieRepository skillKategorieRepository) {
        this.skillKategorieRepository = skillKategorieRepository;
    }

    /**
     * Liefert die aktiven Kategorien in ihrer Anzeigereihenfolge.
     *
     * <p>Nur aktive: Eine abgeschaltete Kategorie soll weder im Formular erscheinen noch in die
     * Zielfunktion des Teamgenerators eingehen. Die zugehoerigen Skillzeilen bleiben in der
     * Datenbank erhalten, falls die Kategorie wieder aktiviert wird.
     *
     * @return {@code 200} mit der Kategorienliste; nie {@code null}, hoechstens leer
     */
    @GetMapping(value = "/skills/lesen", version = ApiVersionConfig.VERSION)
    public ResponseEntity<List<SkillKategorieInfo>> skillKategorienLesen() {
        List<SkillKategorieInfo> kategorien = skillKategorieRepository.aktive().stream()
                .map(SkillKategorieInfo::von)
                .toList();

        return ResponseEntity.ok(kategorien);
    }
}
