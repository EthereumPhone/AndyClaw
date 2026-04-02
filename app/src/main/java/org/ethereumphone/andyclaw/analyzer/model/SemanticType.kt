package org.ethereumphone.andyclaw.analyzer.model

enum class SemanticType(val jsonName: String) {
    BUTTON("button"),
    ICON_BUTTON("icon_button"),
    TOGGLE("toggle"),
    CHECKBOX("checkbox"),
    RADIO_BUTTON("radio_button"),
    TEXT_FIELD("text_field"),
    MENU_ITEM("menu_item"),
    HEADER("header"),
    TEXT("text"),
    IMAGE("image"),
    SEARCH_BAR("search_bar"),
    TAB("tab"),
    SLIDER("slider"),
    SPINNER("spinner"),
    NAV_BUTTON("nav_button"),
    LIST("list"),
    CARD("card"),
    TOOLBAR("toolbar"),
    LINK("link"),
    UNKNOWN("unknown")
}
