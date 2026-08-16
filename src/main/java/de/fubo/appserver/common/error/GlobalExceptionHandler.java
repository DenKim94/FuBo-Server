package de.fubo.appserver.common.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
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

    /** Erwartete fachliche Fehler. */
    @ExceptionHandler(FachlicherFehler.class)
    ProblemDetail behandle(FachlicherFehler e) {
        return problem(e.getCode(), e.getMessage());
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

    /** Baut das Antwortobjekt einheitlich auf. */
    private ProblemDetail problem(Fehlercode code, String meldung) {
        ProblemDetail pd = ProblemDetail.forStatus(code.getStatus());
        pd.setTitle(code.getStatus().getReasonPhrase());
        pd.setDetail(meldung != null ? meldung : code.getStandardMeldung());
        pd.setProperty("code", code.name());
        return pd;
    }
}