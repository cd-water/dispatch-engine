package com.ridematching.trip.adapters.in.rest;

import com.ridematching.domain.trip.IllegalTripTransitionException;
import com.ridematching.trip.application.ConcurrentTripModificationException;
import com.ridematching.trip.application.DriverAlreadyAssignedException;
import com.ridematching.trip.application.TripNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把生命周期失败转换为 RFC 7807 {@code application/problem+json} 响应。
 *
 * <p>它做出的区分正是它的意义所在。有三类不同的事情会阻止一次转换，而调用方对每一类的
 * 反应都必须不同：
 *
 * <ul>
 *   <li><strong>非法转换导致的 409</strong>——行程处于不允许此迁移的状态。重试永远不会
 *       成功；重新读取后重新决策。</li>
 *   <li><strong>并发修改导致的 409</strong>——这次迁移本身很可能是合法的，只是别人先写了。
 *       立即重试才是正确反应，{@code Retry-After} 也这样提示。</li>
 *   <li><strong>404</strong>——不存在这样的行程。</li>
 * </ul>
 *
 * <p>把前两者合并成一个状态码、又不在响应体里加以区分，会让客户端要么在一个永久性拒绝上
 * 永远重试，要么在一个暂时性拒绝上放弃。状态码相同；区分它们的是 {@code title}。
 */
@RestControllerAdvice
public class RestExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RestExceptionHandler.class);

    /**
     * 状态机拒绝了这次迁移。
     *
     * <p>消息同时点明两个状态，因为单凭一句 "409 Conflict" 只会让调用方去猜：是太早了、
     * 太晚了，还是根本就是错的。
     */
    @ExceptionHandler(IllegalTripTransitionException.class)
    public ResponseEntity<ProblemDetail> onIllegalTransition(IllegalTripTransitionException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Transition not allowed");
        log.debug("Rejected transition: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * 别人先写了。可重试，响应头也这样提示。
     *
     * <p>按 debug 记录而非 error：在争用下这正是乐观锁在履行职责，一个繁忙的小时不该被
     * 读成一次事故。
     */
    @ExceptionHandler(ConcurrentTripModificationException.class)
    public ResponseEntity<ProblemDetail> onConcurrentModification(ConcurrentTripModificationException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The trip changed while this request was being handled. Read it again and retry.");
        problem.setTitle("Concurrent modification");
        log.debug("Optimistic lock lost: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header("Retry-After", "1")
                .body(problem);
    }

    /** {@code uniq_driver_active_trip} 索引拒绝了这次写入（ADR-0004）。 */
    @ExceptionHandler(DriverAlreadyAssignedException.class)
    public ResponseEntity<ProblemDetail> onDriverAlreadyAssigned(DriverAlreadyAssignedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "That driver is already on another trip.");
        problem.setTitle("Driver unavailable");
        log.debug("Driver already assigned: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(TripNotFoundException.class)
    public ResponseEntity<ProblemDetail> onNotFound(TripNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Trip not found");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    /** Bean 校验拒绝了请求体——目前只会是缺少取消原因。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> onValidationFailure(MethodArgumentNotValidException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError error : e.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(error.getField(),
                    error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage());
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "The request body failed validation. See 'errors' for the fields concerned.");
        problem.setTitle("Invalid request");
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    /** 格式正确但领域层仍然拒绝的请求——格式错误的 id，未知的枚举值。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> onInvalidArgument(IllegalArgumentException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid request");
        return ResponseEntity.badRequest().body(problem);
    }
}
