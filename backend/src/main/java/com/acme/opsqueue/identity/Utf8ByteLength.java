package com.acme.opsqueue.identity;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;

@Documented
@Constraint(validatedBy = Utf8ByteLength.Validator.class)
@Target({
        ElementType.FIELD,
        ElementType.PARAMETER,
        ElementType.RECORD_COMPONENT,
        ElementType.TYPE_USE
})
@Retention(RetentionPolicy.RUNTIME)
public @interface Utf8ByteLength {
    String message() default "must contain at most {max} UTF-8 bytes";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    int max();

    final class Validator implements ConstraintValidator<Utf8ByteLength, String> {
        private int max;

        @Override
        public void initialize(Utf8ByteLength constraint) {
            max = constraint.max();
        }

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null
                    || value.getBytes(StandardCharsets.UTF_8).length <= max;
        }
    }
}
