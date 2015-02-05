GoldenGate
==========
Android annotation processor for generating type safe javascript bindings (Bridges). The library is very similar in usage to something like retrofit in that only an interface has to be declared and annotated. This annotated interface is at compile time used to generate an type safe wrapper around a webview for interfacing with the javascript.

Usage
-----
Start by creating an interface and annotate it with `@Bridge` and also add a method which you want to call in javascript.
```java
@Bridge
interface MyJavascript {
	void alert(String message);
}
```

This will automatically generate a class called `MyJavascriptBridge` which is the implementation which wraps a webview and implements the interface we just defined. Now we have a compile time checked type safe way of opening a javascript alert.
```java
Webview webview = ...;
MyJavascript bridge = new MyJavascriptBridge(webview);
bridge.alert("Hi there!");
```

The above example is just a fire and forget example though. We often want to get some result back. For this we have `Callback<T>`, because javascript runs asynchronously we can't just return this value and must therefor use a callback. The callback argument must allways be the argument specified last in the method decleration. Here is an example of `Callback<T>`.
```java
@Bridge
interface MyJavascript {
	void calculateSomeValue(Callback<Integer> value);
}
```

That's it for simple usage! There are two other annotations for customized usage, `@Method` and `@Property`. `@Method` can be used to override the name of the method on the javascript side of the bridge (The java name of the method is automatically chosen if this annotation is not supplied).
```java
@Bridge
interface MyJavascript {
	@Method("console.Log")
	void alert(String message);
}
```

The `@Property` annotation should be used for when setting or getting a property on the javascript side of things. In this case the method may only have one parameter (either a callback for result or a value which should be set). Just like with the `@Method` delaration a custom name can be chosen for the property. The default name for properties however is the name of the parameter to the method.
```java
@Bridge
interface MyJavascript {
	@Property("window.innerHeight")
	void getWindowHeight(Callback<Integer> height);
}
```