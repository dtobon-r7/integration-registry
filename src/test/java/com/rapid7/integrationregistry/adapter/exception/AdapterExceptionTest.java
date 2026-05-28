package com.rapid7.integrationregistry.adapter.exception;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

class AdapterExceptionTest {

    @Test
    void classModifier_shouldBeAbstract_whenInspected() {
        // Arrange / Act
        int modifiers = AdapterException.class.getModifiers();

        // Assert — ADR-001 family-parent rule: AdapterException is abstract
        // so it cannot be thrown directly; concrete subclasses are the
        // failure modes.
        assertThat(Modifier.isAbstract(modifiers)).isTrue();
    }

    @Test
    void classModifier_shouldNotBeFinal_perAdr001SharedInvariant() {
        // Arrange / Act
        int modifiers = AdapterException.class.getModifiers();

        // Assert — ADR-001 shared invariant: exception classes are not final
        // so future refactors can subclass without a breaking change.
        assertThat(Modifier.isFinal(modifiers)).isFalse();
    }

    @Test
    void serialVersionUID_shouldBePresentAndPrivateStaticFinalLong_whenInspected() throws Exception {
        // Arrange
        Field field = AdapterException.class.getDeclaredField("serialVersionUID");

        // Act / Assert — ADR-001 shared invariant
        assertThat(Modifier.isPrivate(field.getModifiers())).isTrue();
        assertThat(Modifier.isStatic(field.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
        assertThat(field.getType()).isEqualTo(long.class);
    }

    @Test
    void isTransient_shouldBeAbstract_whenInspected() throws Exception {
        // Arrange
        Method method = AdapterException.class.getDeclaredMethod("isTransient");

        // Act / Assert
        assertThat(Modifier.isAbstract(method.getModifiers())).isTrue();
        assertThat(method.getReturnType()).isEqualTo(boolean.class);
    }

    @Test
    void reasonCode_shouldBeAbstract_whenInspected() throws Exception {
        // Arrange
        Method method = AdapterException.class.getDeclaredMethod("reasonCode");

        // Act / Assert
        assertThat(Modifier.isAbstract(method.getModifiers())).isTrue();
        assertThat(method.getReturnType()).isEqualTo(String.class);
    }
}
