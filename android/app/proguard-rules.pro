# R8 keep rules.
#
# Chaquopy 会生成一份同样内容的规则到 build/python/proguard-rules.pro；
# 这里显式保留一份，避免 release 构建只依赖生成文件 —— 生成文件被清掉时
# 不会出现「运行时找不到 Python 桥接类」这种只在 release 才炸的问题。
-keep class com.chaquo.python.** { * ; }

# Chaquopy 的 get_sam 需要这些 Kotlin 函数类型
-keep class kotlin.jvm.functions.** { * ; }
-keep class kotlin.jvm.internal.FunctionBase { * ; }
-keep class kotlin.reflect.KAnnotatedElement { *; }

# Chaquopy 自身用到的注解在运行时不可见，缺了只会产生 R8 警告
-dontwarn org.jetbrains.annotations.NotNull
