package com.flipboard.goldengate;

import android.annotation.TargetApi;
import android.os.Build;
import android.webkit.WebView;

import com.google.gson.reflect.TypeToken;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.processing.Filer;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.type.TypeMirror;

public class BridgeInterface {

    public final String name;
    public final boolean isDebug;
    private TypeMirror type;
    private ArrayList<BridgeMethod> bridgeMethods = new ArrayList<>();
    private ArrayList<BridgeProperty> bridgeProperties = new ArrayList<>();

    public BridgeInterface(Element element) {
        this.name = element.getSimpleName().toString();
        this.isDebug = element.getAnnotation(Debug.class) != null;
        this.type = element.asType();

        for (Element method : element.getEnclosedElements()) {
            if (method.getAnnotation(Property.class) != null) {
                bridgeProperties.add(new BridgeProperty((ExecutableElement) method));
            } else {
                bridgeMethods.add(new BridgeMethod((ExecutableElement) method));
            }
        }
    }

    public void writeToFiler(Filer filer) throws IOException {
        String packageName = getPackageName(type);

        // Build Bridge class
        TypeSpec.Builder bridge = TypeSpec.classBuilder(name + "Bridge")
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(TypeName.get(type))
                .superclass(JavaScriptBridge.class)
                .addField(ClassName.get(packageName, name + "Bridge", "ResultBridge"), "resultBridge", Modifier.PRIVATE)
                .addField(AtomicLong.class, "receiverIds", Modifier.PRIVATE);

        Type callbacksMapType = new TypeToken<Map<Long, WeakReference<Callback<String>>>>(){}.getType();
        Type callbackType = new TypeToken<Callback<String>>(){}.getType();

        // Generate the result bridge
        bridge.addType(TypeSpec.classBuilder("ResultBridge")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .addField(FieldSpec.builder(callbacksMapType, "callbacks")
                        .initializer("new $T<>()", HashMap.class)
                        .build())
                .addMethod(MethodSpec.methodBuilder("registerCallback")
                        .addParameter(long.class, "receiver")
                        .addParameter(callbackType, "cb")
                        .addCode(CodeBlock.builder().addStatement("callbacks.put($N, new $T($N))", "receiver", WeakReference.class, "cb").build())
                        .build())
                .addMethod(MethodSpec.methodBuilder("onResult")
                        .addModifiers(Modifier.PUBLIC)
                        .addAnnotation(ClassName.get("android.webkit", "JavascriptInterface"))
                        .addParameter(String.class, "result")
                        .addCode(CodeBlock.builder()
                                .beginControlFlow("try")
                                    .addStatement("$T $N = new $T($N)", ClassName.get("org.json", "JSONObject"), "json", ClassName.get("org.json", "JSONObject"), "result")
                                    .addStatement("$T $N = $N.getLong($S)", long.class, "receiver", "json", "receiver")
                                    .addStatement("$T $N = $N.get($S).toString()", String.class, "realResult", "json", "result")
                                    .addStatement("$T $N = $N.get($N).get()", callbackType, "callback", "callbacks", "receiver")
                                    .beginControlFlow("if ($N != null) ", "callback")
                                        .addStatement("$N.onResult($N)", "callback", "realResult")
                                    .endControlFlow()
                                .nextControlFlow("catch (org.json.JSONException e)")
                                    .addStatement("$N.printStackTrace()", "e")
                                .endControlFlow()
                                .build())
                        .build())
                .build());

        // Add Bridge constructor using globally configured json serializer
        bridge.addMethod(
                MethodSpec.constructorBuilder()
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter(WebView.class, "webView")
                        .addStatement("super($N)", "webView")
                        .addStatement("init()")
                        .build()
        );

        // Add Bridge constructor using custom json serializer
        bridge.addMethod(
                MethodSpec.constructorBuilder()
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter(WebView.class, "webView")
                        .addParameter(JsonSerializer.class, "jsonSerializer")
                        .addStatement("super($N, $N)", "webView", "jsonSerializer")
                        .addStatement("init()")
                        .build()
        );

        // Add common init method for both constructors
        bridge.addMethod(
                MethodSpec.methodBuilder("init")
                    .addModifiers(Modifier.PRIVATE)
                    .addStatement("this.$N = new ResultBridge()", "resultBridge")
                    .addStatement("this.$N = new $T()", "receiverIds", AtomicLong.class)
                    .addStatement("this.$N.addJavascriptInterface($N, $L)", "webView", "resultBridge", "\"" + name + "\"")
                    .addCode("evaluateJavascript(\n" +
                            "                \"function GoldenGate$$$$CreateCallback(receiver) {\" +\n" +
                            "                \"    return function(result) {\" +\n" +
                            "                \"        $N.onResult(JSON.stringify({receiver: receiver, result: JSON.stringify(result)}))\" +\n" +
                            "                \"    }\" +\n" +
                            "                \"}\");", name)
                    .build()
        );

        bridge.addMethod(
                MethodSpec.methodBuilder("evaluateJavascript")
                        .addModifiers(Modifier.PRIVATE)
                        .addAnnotation(AnnotationSpec.builder(TargetApi.class).addMember("value", "$T.VERSION_CODES.KITKAT", Build.class).build())
                        .addParameter(String.class, "javascript")
                        .beginControlFlow("if ($T.VERSION.SDK_INT >= $T.VERSION_CODES.KITKAT)", Build.class, Build.class)
                        .addStatement("this.$N.evaluateJavascript($N, null)", "webView", "javascript")
                        .nextControlFlow("else ")
                        .addStatement("this.$N.loadUrl(\"javascript:\" + $N)", "webView", "javascript")
                        .endControlFlow()
                        .build()
        );

        // Add Bridge methods
        for (BridgeMethod method : bridgeMethods) {
            bridge.addMethod(method.toMethodSpec(this));
        }

        // Add Bridge property methods
        for (BridgeProperty property : bridgeProperties) {
            bridge.addMethod(property.toMethodSpec(this));
        }

        // Write source
        JavaFile javaFile = JavaFile.builder(packageName, bridge.build()).build();
        javaFile.writeTo(filer);
    }

    private String getPackageName(TypeMirror type) {
        String[] parts = type.toString().split("\\.");
        return join(Arrays.copyOfRange(parts, 0, parts.length - 1), ".");
    }

    private String join(String[] strings, String sep) {
        StringBuilder stringBuilder = new StringBuilder();
        for (int i = 0; i < strings.length; i++) {
            stringBuilder.append(strings[i]);
            if (i + 1 < strings.length) {
                stringBuilder.append(sep);
            }
        }
        return stringBuilder.toString();
    }

}
