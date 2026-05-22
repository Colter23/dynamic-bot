package top.colter.dynamic

public data class MainDynamicConfig(
    val templates: Map<String, String> = mapOf(DEFAULT_TEMPLATE_NAME to DEFAULT_TEMPLATE),
) {
    public companion object {
        public const val CONFIG_ID: String = "main"
        public const val DEFAULT_TEMPLATE_NAME: String = "default"
        public const val DEFAULT_TEMPLATE: String = "{publisher.name} 发布了新动态\n{dynamic.text}\n{dynamic.link}"
    }
}
