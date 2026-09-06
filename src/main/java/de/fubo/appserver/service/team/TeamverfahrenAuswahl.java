package de.fubo.appserver.service.team;

import de.fubo.appserver.domain.config.AlgorithmType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Waehlt das Verfahren, das {@code configs.app_config.algorithm_type} nennt
 * ({@code S5_ALGORITHMUS.md}, 5.3).
 *
 * <h2>Eine Karte, kein {@code switch}</h2>
 * Spring reicht alle {@link Teamverfahren}-Beans herein, die Karte entsteht daraus. <b>Ein
 * drittes Verfahren kaeme damit ohne Aenderung an einer aufrufenden Zeile dazu</b> - ein
 * {@code switch} muesste an jeder Stelle nachgezogen werden, an der gewaehlt wird, und die
 * vergessene faellt erst zur Laufzeit auf.
 *
 * <h2>Vollstaendig oder gar nicht - und zwar beim Start</h2>
 * Fehlt zu einem Wert des Aufzaehlungstyps eine Bean, bricht der Kontextstart ab. Die
 * Alternative waere ein {@code null} mitten im Generierungslauf: Der Nutzer bekaeme einen
 * {@code 500}, nachdem er auf "Teams generieren" gedrueckt hat, und die Ursache stuende in
 * keinem Zusammenhang mit seiner Handlung. Zwei Beans fuer denselben Typ sind aus demselben
 * Grund ein Startabbruch - welche von beiden gewaenne, entschiede sonst die Reihenfolge der
 * Klassenpfadsuche.
 */
@Component
public class TeamverfahrenAuswahl {

    private final Map<AlgorithmType, Teamverfahren> verfahren;

    public TeamverfahrenAuswahl(List<Teamverfahren> beans) {
        Map<AlgorithmType, Teamverfahren> karte = new EnumMap<>(AlgorithmType.class);
        for (Teamverfahren bean : beans) {
            Teamverfahren vorher = karte.put(bean.typ(), bean);
            if (vorher != null) {
                throw new IllegalStateException("Zum Verfahren " + bean.typ()
                        + " gibt es mehr als eine Bean: "
                        + vorher.getClass().getSimpleName() + " und "
                        + bean.getClass().getSimpleName() + ".");
            }
        }
        for (AlgorithmType typ : AlgorithmType.values()) {
            if (!karte.containsKey(typ)) {
                throw new IllegalStateException(
                        "Zum Verfahren " + typ + " gibt es keine Bean; "
                                + "configs.app_config.algorithm_type laesst diesen Wert zu.");
            }
        }
        this.verfahren = karte;
    }

    /**
     * Liefert das Verfahren zu einem Konfigurationswert.
     *
     * <p>Die Vollstaendigkeit ist beim Start geprueft, ein {@code null} kann hier also nicht
     * herauskommen.
     *
     * @param typ Wert aus {@code configs.app_config.algorithm_type}
     */
    public Teamverfahren fuer(AlgorithmType typ) {
        return verfahren.get(typ);
    }
}
