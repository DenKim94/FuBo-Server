package de.fubo.appserver.common.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Uebersetzt Ausnahmen in ein einheitliches Problem-Detail-Objekt (RFC 9457). */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Erwartete fachliche Fehler.
     *
     * <p>Rueckgabetyp ist {@link ResponseEntity} und nicht mehr nur {@link ProblemDetail}:
     * Eine Restwartezeit gehoert zusaetzlich in den {@code Retry-After}-Header (RFC 9110,
     * Abschnitt 10.2.3), und Header lassen sich nur ueber die {@code ResponseEntity}
     * setzen. Der Koerper ist unveraendert dasselbe Problem-Detail.
     */
    @ExceptionHandler(FachlicherFehler.class)
    ResponseEntity<ProblemDetail> behandle(FachlicherFehler e) {
        ProblemDetail pd = problem(e.getCode(), e.getMessage());

        ResponseEntity.BodyBuilder antwort = ResponseEntity.status(e.getCode().getStatus());

        Long wartesekunden = e.getWartesekunden();
        if (wartesekunden != null) {
            // Doppelt gefuehrt, und zwar mit Absicht: Der Header ist die genormte Form,
            // die auch ein Zwischenspeicher oder eine Bibliothek auswertet; das Feld im
            // Koerper erspart dem Frontend den Zugriff auf die Header, der bei einer
            // Cross-Origin-Antwort ohne Access-Control-Expose-Headers gar nicht moeglich
            // waere.
            pd.setProperty("wartesekunden", wartesekunden);
            antwort = antwort.header(HttpHeaders.RETRY_AFTER, Long.toString(wartesekunden));
        }
        return antwort.body(pd);
    }

    /**
     * Fehlende Berechtigung aus der Methodensicherheit (@PreAuthorize, ab S3).
     * Ohne diesen Handler faengt der Auffangzweig unten die AccessDeniedException
     * und macht aus einem 403 ein 500.
     */
    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail behandle(AccessDeniedException e) {
        return problem(Fehlercode.KEINE_BERECHTIGUNG, null);
    }

    /**
     * Sperrkonflikt aus dem Optimistic Locking (A5, ab S3).
     *
     * <p><b>Warum es diesen Handler zusaetzlich zum Versionsvergleich im Dienst gibt:</b> Der
     * Vergleich in {@code ConfigService#aktualisieren} liest, der Commit schreibt - dazwischen
     * liegt ein Fenster, in dem eine zweite Transaktion dieselbe Zeile aendern kann. Hibernate
     * bemerkt das beim Schreiben und wirft. Ohne diesen Zweig fiele die Ausnahme in den
     * Auffangzweig und kaeme als {@code 500 INTERNER_FEHLER} heraus - ein Bedienfehler, der wie
     * ein Serverfehler aussieht.
     *
     * <p>Er traegt ab S4 auch die Termine und ab S6 die Ergebnisse; deren {@code version}-Spalten
     * fuehren in dieselbe Ausnahme.
     *
     * <p>Die Ursache geht ins Log, nicht in die Antwort: Die Meldung von Hibernate nennt
     * Entity-Klasse und Schluessel.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail behandleSperrkonflikt(ObjectOptimisticLockingFailureException e) {
        LOG.debug("Sperrkonflikt beim Schreiben", e);
        return problem(Fehlercode.DATEN_VERALTET, null);
    }

    /** Auffangzweig: alles Unerwartete. */
    @ExceptionHandler(Exception.class)
    ProblemDetail behandleUnerwartet(Exception e) {
        LOG.error("Unerwarteter Fehler", e);   // Details ins Log, nicht in die Antwort
        return problem(Fehlercode.INTERNER_FEHLER, null);
    }

    /** Bean Validation: gleiche Huelle, zusaetzlich die betroffenen Felder. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {

        Map<String, String> felder = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(FieldError::getField,
                        f -> Objects.requireNonNullElse(f.getDefaultMessage(), "ungueltig"),
                        (a, b) -> a));

        Fehlercode code = Fehlercode.EINGABE_UNGUELTIG;
        ProblemDetail pd = problem(code, null);
        pd.setProperty("felder", felder);

        // code.getStatus() ist ein HttpStatus und damit ein HttpStatusCode.
        return handleExceptionInternal(ex, pd, headers, code.getStatus(), request);
    }

    /**
     * Unlesbarer oder unpassender Anfragekoerper - fehlerhaftes JSON, ein falscher
     * Feldtyp oder ein unbekannter Aufzaehlungswert.
     *
     * <p>Ohne diese Ueberschreibung antwortete die Basisklasse zwar ebenfalls mit
     * {@code 400}, aber ohne das Feld {@code code}. Das Frontend haette dann zwei
     * Fehlerformate zu unterscheiden - genau das, was der einheitliche Vertrag
     * (Abschnitt 10.2) vermeiden soll.
     *
     * <p><b>Die Ursache steht bewusst nicht in der Antwort.</b> Die Meldung von Jackson
     * nennt Klassennamen und Feldpfade; das gehoert ins Log, nicht zum Aufrufer.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {

        LOG.debug("Anfragekoerper nicht lesbar", ex);

        Fehlercode code = Fehlercode.EINGABE_UNGUELTIG;
        ProblemDetail pd = problem(code, "Der Anfragekoerper ist unvollstaendig oder fehlerhaft.");

        return handleExceptionInternal(ex, pd, headers, code.getStatus(), request);
    }

    /** Baut das Antwortobjekt einheitlich auf. */
    private ProblemDetail problem(Fehlercode code, String meldung) {
        ProblemDetail pd = ProblemDetail.forStatus(code.getStatus());
        pd.setTitle(code.getStatus().getReasonPhrase());
        pd.setDetail(meldung != null ? meldung : code.getStandardMeldung());
        pd.setProperty("code", code.name());
        return pd;
    }
}
