package de.fubo.appserver.common.security;

import de.fubo.appserver.common.error.Fehlercode;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Schreibt einen Fehler direkt in die Antwort bei Authorisierungsfehlern vor dem DispatcherServlet. <br/>
 * Ohne diese Klasse hätte deine API zwei Fehlerformate:<br/>
 * Fachliche Fehler kämen als JSON mit code, ein abgelaufenes Cookie dagegen als 401 mit leerem Body.<br/>
 * Das Frontend müsste somit zwei Fälle unterscheiden<br/>
 * */
@Component
public class AuthorizationExceptionHandler {
    private final ObjectMapper mapper;

    public AuthorizationExceptionHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Erzeugt dieselbe Huelle wie der GlobalExceptionHandler. */
    public void schreibeFehlermeldung(HttpServletResponse res, Fehlercode code) throws IOException {
        ProblemDetail pd = ProblemDetail.forStatus(code.getStatus());
        pd.setTitle(code.getStatus().getReasonPhrase());
        pd.setDetail(code.getStandardMeldung());
        pd.setProperty("code", code.name());

        res.setStatus(code.getStatus().value());
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(res.getOutputStream(), pd);
    }
}
