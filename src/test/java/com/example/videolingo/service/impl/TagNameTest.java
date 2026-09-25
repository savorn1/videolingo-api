package com.example.videolingo.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TagNameTest {

    @Test
    void namesAreTrimmedHashStrippedAndSpaceCollapsed() {
        assertEquals("slang", TagServiceImpl.normalizeName("  #slang "));
        assertEquals("JLPT N5", TagServiceImpl.normalizeName("##  JLPT \t  N5"));
        assertEquals("C#", TagServiceImpl.normalizeName("C#"), "only leading #s are dropped");
        assertEquals("", TagServiceImpl.normalizeName(" # "));
    }

    @Test
    void caseIsKeptAsTyped() {
        assertEquals("Business English", TagServiceImpl.normalizeName("Business   English"));
    }
}
