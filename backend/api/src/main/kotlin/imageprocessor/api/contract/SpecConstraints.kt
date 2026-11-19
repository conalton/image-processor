package imageprocessor.api.contract

import imageprocessor.shared.contract.models.CreateTaskRequest
import jakarta.validation.Validation
import jakarta.validation.Validator
import org.hibernate.validator.HibernateValidator
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator
import java.util.Locale

/**
 * Checks a create request against the constraints of the spec, which Fabrikt writes onto the models as Jakarta
 * Validation annotations; kotlinx.serialization does not check them. Only for the create request: without
 * `-Xemit-jvm-type-annotations` the elements of a `List<@Valid ...>` are not checked.
 */
class SpecConstraints {
    // Hibernate Validator ships translated messages; without a fixed locale the API's text would follow the JVM's.
    private val validator: Validator = Validation.byProvider(HibernateValidator::class.java)
        .configure()
        .defaultLocale(Locale.ROOT)
        .messageInterpolator(ParameterMessageInterpolator())
        .buildValidatorFactory()
        .validator

    fun violations(request: CreateTaskRequest): List<String> =
        validator.validate(request).map { violation -> "${violation.propertyPath}: ${violation.message}" }.sorted()
}
