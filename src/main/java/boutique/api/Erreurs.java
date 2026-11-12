package boutique.api;

import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Toutes les erreurs au meme format, Problem Details (RFC 9457) : type, title, status, detail, instance.
 * Pour une erreur de validation, Spring ne dit pas QUEL champ est faux : on l'ajoute dans « erreurs ».
 */
@RestControllerAdvice
public class Erreurs extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        ProblemDetail pd = ex.getBody();
        pd.setDetail("La commande est invalide.");
        Map<String, String> champs = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(FieldError::getField, e -> String.valueOf(e.getDefaultMessage()),
                        (a, b) -> a, TreeMap::new));
        pd.setProperty("erreurs", champs);
        return handleExceptionInternal(ex, pd, headers, status, request);
    }
}
