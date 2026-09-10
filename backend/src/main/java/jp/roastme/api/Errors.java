package jp.roastme.api;

import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class Errors {

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ProblemDetail> app(ApiException e) {
    var p = ProblemDetail.forStatusAndDetail(
      HttpStatusCode.valueOf(e.status),
      e.getMessage()
    );
    p.setProperty("code", e.code);
    return ResponseEntity.status(e.status).body(p);
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
  })
  ResponseEntity<ProblemDetail> validation(Exception e) {
    return app(
      new ApiException(400, "VALIDATION_ERROR", "入力内容を確認してください。")
    );
  }
}
