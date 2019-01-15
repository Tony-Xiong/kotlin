/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.expressions

import org.jetbrains.kotlin.fir.types.FirType
import org.jetbrains.kotlin.fir.visitors.FirVisitor

// is/!is/as/as?
interface FirExpressionWithType : FirExpression {
    val argument: FirExpression

    val type: FirType

    val operation: FirOperation

    override fun <R, D> accept(visitor: FirVisitor<R, D>, data: D): R =
        visitor.visitExpressionWithType(this, data)
}