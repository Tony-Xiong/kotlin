/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.expressions

// May be this structure isn't needed
enum class FirOperation {
    // Used both for binary/unary
    PLUS,
    MINUS,
    // Binary
    TIMES,
    DIV,
    REM,
    RANGE,
    EQ,
    NOT_EQ,
    IDENTITY,
    NOT_IDENTITY,
    LT,
    GT,
    LT_EQ,
    GT_EQ,
    AND,
    OR,
    IN,
    NOT_IN,
    ASSIGN,
    PLUS_ASSIGN,
    MINUS_ASSIGN,
    TIMES_ASSIGN,
    DIV_ASSIGN,
    REM_ASSIGN,

    // Unary
    INCR,
    DECR,
    EXCL,
    // Type
    IS,
    NOT_IS,
    AS,
    SAFE_AS,
    // All non-standard operations (infix calls)
    OTHER;
}