package com.ridematching.dispatch.adapters.in.rest;

import com.ridematching.dispatch.application.DriverAlreadyAssignedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把异常转换为 RFC 7807 {@code application/problem+json} 响应。
 *
 * <p>放在 advice 里而不是控制器上，这样每个端点响应方式一致，新增控制器时也不必记得把
 * 处理器复制过去。
 *
 * <p>它存在的原因：没有针对校验和解析失败的处理器时，就轮到 Spring 默认的错误控制器作答，
 * 它返回 {@code {"timestamp","status":400,"error":"Bad Request","path"}}——没有字段名，没有
 * 原因。照着它集成的客户端只能去猜六个字段里哪个错了。简洁对于一个无法判断哪些信息可以披露
 * 的框架是正确的默认值；对于一个知道答案的 API 则是错误答案。
 */
@RestControllerAdvice
public class RestExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RestExceptionHandler.class);

    /**
     * Bean 校验拒绝了一个或多个字段。
     *
     * <p>每个违规字段都连同其消息一起点名，因为对一个六字段载荷只说 "400 Bad Request"
     * 不会告诉调用方任何可行动的信息。字段名和约束消息是把客户端自己的输入回显回去——
     * 不泄露任何内部细节。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> onValidationFailure(MethodArgumentNotValidException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError error : e.getBindingResult().getFieldErrors()) {
            // 每个字段只保留第一条消息：同一字段上的重复约束只会制造噪音而非清晰。
            errors.putIfAbsent(error.getField(),
                    error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage());
        }
        e.getBindingResult().getGlobalErrors().forEach(error ->
                errors.putIfAbsent(error.getObjectName(), error.getDefaultMessage()));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "The request body failed validation. See 'errors' for the fields concerned.");
        problem.setTitle("Invalid request");
        problem.setProperty("errors", errors);

        log.debug("Validation failed: {}", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * 请求体完全无法解析——JSON 格式错误，或某个值的类型不对。
     *
     * <p>解析器自己的消息会被记录，但刻意不返回。Jackson 的消息会点出 Java 类和字段路径
     * （"Cannot deserialize value of type com.ridematching..."），这等于递给攻击者一张内部
     * 结构图，而对一个本来就知道自己发了什么的合法客户端毫无帮助。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> onUnreadableBody(HttpMessageNotReadableException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Request body is not valid JSON, or a field has the wrong type. "
                        + "Expected: riderId (UUID), pickupLat, pickupLng, dropoffLat, dropoffLng "
                        + "(numbers), vehicleClass (STANDARD | XL | PREMIUM, optional).");
        problem.setTitle("Malformed request body");

        log.debug("Unreadable request body: {}",
                e.getMostSpecificCause().getMessage());
        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * {@code uniq_driver_active_trip} 索引拒绝了写入：另一个 worker 持有这个司机。
     *
     * <p>是 409，不是 500。在争用下这正是 ADR-0004 的保证按设计生效，所以按 debug 记录并
     * 计数，而不是当作错误抛出——否则一个繁忙的派单小时会用代表成功的异常填满日志。
     */
    @ExceptionHandler(DriverAlreadyAssignedException.class)
    public ResponseEntity<ProblemDetail> onDriverAlreadyAssigned(DriverAlreadyAssignedException e) {
        log.debug("Driver already assigned: {}", e.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "That driver was assigned to another ride.");
        problem.setTitle("Driver unavailable");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /** 领域校验拒绝了一个格式本来正确的请求（坐标越界、未知枚举值）。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> onInvalidArgument(IllegalArgumentException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid request");
        return ResponseEntity.badRequest().body(problem);
    }
}
