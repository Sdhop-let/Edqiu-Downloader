package dev.drewhamilton.poko

/**
 * 本地 stub：material-color-utilities 源码内嵌编译时替代 Poko 编译器插件。
 * Poko 原本在编译期生成 equals/hashCode/toString；引擎内部不依赖这些类的
 * 结构相等语义（经 grep 核实，比较均为枚举/字符串），故 stub 为空注解即可。
 * （2026-09-14，与 mcu-pipeline 5.0.1 同源引擎内嵌方案配套）
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class Poko
