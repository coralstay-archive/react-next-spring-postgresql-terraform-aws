package com.example.board.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedArrayType;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import jakarta.validation.Constraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory;

import com.example.board.BoardApplication;

/**
 * TASK-28#2 B2: 400 {@code validation} 의 {@code fields} 문구는 Bean Validation 제약의 {@code message} 다. 명시하지 않으면
 * Hibernate Validator 기본 영어 문구("must not be blank" 등)가 화면에 그대로 나가므로, src/main 의 모든 제약(필드·레코드 컴포넌트·
 * 메서드·파라미터·타입 인자 위)이 한글을 담은 message 를 갖는지 리플렉션으로 확인한다.
 */
class ValidationMessageTest {

	private static final Pattern HANGUL = Pattern.compile("[가-힣]");

	@Test
	@DisplayName("TASK-28#2 TASK-28#B2 src/main 의 모든 Bean Validation 제약은 한국어 message 를 명시한다")
	void everyConstraintHasKoreanMessage() throws Exception {
		List<String> found = new ArrayList<>();
		List<String> offenders = new ArrayList<>();
		for (Class<?> type : mainClasses()) {
			check(type, found, offenders);
		}
		// 스캔이 실제로 DTO 제약을 보고 있는지(경로가 바뀌어 0개를 훑고 통과하는 일이 없게).
		assertThat(found).hasSizeGreaterThan(20)
			.anyMatch(where -> where.contains("SignupRequest"))
			.anyMatch(where -> where.contains("PostRequests$Create"));
		assertThat(offenders).isEmpty();
	}

	@Test
	@DisplayName("TASK-28#2 TASK-28#B2 검사기는 message 가 없거나 영어인 제약을 잡아낸다")
	void detectorCatchesDefaultAndEnglishMessages() {
		List<String> found = new ArrayList<>();
		List<String> offenders = new ArrayList<>();
		check(Fixture.class, found, offenders);
		// 레코드 컴포넌트의 제약은 필드·생성자 파라미터·접근자에도 복사되므로 위치별로 여러 번 잡힌다.
		assertThat(offenders).noneMatch(o -> o.contains("korean"))
			.anyMatch(o -> o.contains("missing") && o.contains("NotBlank"))
			.anyMatch(o -> o.contains("english") && o.contains("Size"))
			.anyMatch(o -> o.contains("items") && o.contains("NotBlank"));
		assertThat(found).anyMatch(where -> where.contains("korean"));
	}

	@SuppressWarnings("unused")
	record Fixture(@NotBlank String missing, @Size(max = 3, message = "too long") String english,
			@NotBlank(message = "입력하세요") String korean, List<@NotBlank String> items) {
	}

	private static List<Class<?>> mainClasses() throws IOException, ClassNotFoundException {
		URL mainRoot = BoardApplication.class.getProtectionDomain().getCodeSource().getLocation();
		var resolver = new PathMatchingResourcePatternResolver();
		MetadataReaderFactory readers = new SimpleMetadataReaderFactory(resolver);
		ClassLoader loader = BoardApplication.class.getClassLoader();
		List<Class<?>> classes = new ArrayList<>();
		for (Resource resource : resolver.getResources("classpath*:com/example/board/**/*.class")) {
			String name = readers.getMetadataReader(resource).getClassMetadata().getClassName();
			Class<?> type = Class.forName(name, false, loader);
			var source = type.getProtectionDomain().getCodeSource();
			if (source != null && Objects.equals(source.getLocation(), mainRoot)) {
				classes.add(type);
			}
		}
		assertThat(classes).isNotEmpty();
		return classes;
	}

	private static void check(Class<?> type, List<String> found, List<String> offenders) {
		String owner = type.getName();
		if (!type.isAnnotation()) {
			inspect(owner, type, found, offenders);
		}
		if (type.isRecord()) {
			for (RecordComponent component : type.getRecordComponents()) {
				String where = owner + "#" + component.getName();
				inspect(where, component, found, offenders);
				inspectType(where, component.getAnnotatedType(), found, offenders);
			}
		}
		for (var field : type.getDeclaredFields()) {
			if (field.isSynthetic()) {
				continue;
			}
			String where = owner + "." + field.getName();
			inspect(where, field, found, offenders);
			inspectType(where, field.getAnnotatedType(), found, offenders);
		}
		List<Executable> executables = new ArrayList<>(List.of(type.getDeclaredMethods()));
		executables.addAll(List.of(type.getDeclaredConstructors()));
		for (Executable executable : executables) {
			if (executable.isSynthetic()) {
				continue;
			}
			String where = owner + "." + executable.getName() + "()";
			inspect(where, executable, found, offenders);
			if (executable instanceof Method method) {
				inspectType(where, method.getAnnotatedReturnType(), found, offenders);
			}
			for (Parameter parameter : executable.getParameters()) {
				String param = where + "[" + parameter.getName() + "]";
				inspect(param, parameter, found, offenders);
				inspectType(param, parameter.getAnnotatedType(), found, offenders);
			}
		}
	}

	private static void inspectType(String where, AnnotatedType type, List<String> found, List<String> offenders) {
		if (type instanceof AnnotatedParameterizedType parameterized) {
			for (AnnotatedType argument : parameterized.getAnnotatedActualTypeArguments()) {
				inspect(where + "<>", argument, found, offenders);
				inspectType(where + "<>", argument, found, offenders);
			}
		}
		else if (type instanceof AnnotatedArrayType array) {
			inspect(where + "[]", array.getAnnotatedGenericComponentType(), found, offenders);
			inspectType(where + "[]", array.getAnnotatedGenericComponentType(), found, offenders);
		}
	}

	private static void inspect(String where, AnnotatedElement element, List<String> found, List<String> offenders) {
		for (Annotation annotation : element.getDeclaredAnnotations()) {
			constraints(annotation).forEach(constraint -> {
				String label = where + " @" + constraint.annotationType().getSimpleName();
				found.add(label);
				String message = message(constraint);
				if (message == null || message.startsWith("{") || !HANGUL.matcher(message).find()) {
					offenders.add(label + " message=" + message);
				}
			});
		}
	}

	/** 제약 애너테이션 자신, 또는 반복 컨테이너(@Size.List 등) 안의 제약들. */
	private static Stream<Annotation> constraints(Annotation annotation) {
		Class<? extends Annotation> kind = annotation.annotationType();
		if (kind.isAnnotationPresent(Constraint.class)) {
			return Stream.of(annotation);
		}
		try {
			Method value = kind.getDeclaredMethod("value");
			if (value.getReturnType().isArray()
					&& value.getReturnType().getComponentType().isAnnotationPresent(Constraint.class)) {
				return Stream.of((Annotation[]) value.invoke(annotation));
			}
		}
		catch (ReflectiveOperationException ex) {
			// value() 가 없는 일반 애너테이션
		}
		return Stream.empty();
	}

	private static String message(Annotation constraint) {
		try {
			return (String) constraint.annotationType().getMethod("message").invoke(constraint);
		}
		catch (ReflectiveOperationException ex) {
			return null;
		}
	}

}
