/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea.quickfix

import com.intellij.testFramework.TestDataPath
import org.jetbrains.kotlin.idea.quickfix.replaceWith.DeprecatedSymbolUsageFix
import org.jetbrains.kotlin.idea.quickfix.replaceWith.ReplaceWith
import org.jetbrains.kotlin.idea.test.KotlinLightCodeInsightFixtureTestCase
import org.jetbrains.kotlin.idea.test.ProjectDescriptorWithStdlibSources
import org.jetbrains.kotlin.idea.util.application.executeWriteCommand
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.psiUtil.parents
import org.jetbrains.kotlin.test.JUnit3RunnerWithInners
import org.jetbrains.kotlin.test.KotlinTestUtils
import org.jetbrains.kotlin.test.TestMetadata
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstance
import org.junit.runner.RunWith

@TestMetadata("idea/testData/quickfix.special")
@TestDataPath("\$PROJECT_ROOT")
@RunWith(JUnit3RunnerWithInners::class)
class DeprecatedSymbolUsageFixSpecialTest : KotlinLightCodeInsightFixtureTestCase() {
    override fun getTestDataPath() = KotlinTestUtils.getHomeDirectory()
    override fun getProjectDescriptor() = ProjectDescriptorWithStdlibSources.INSTANCE

    private val TEST_DATA_DIR = "idea/testData/quickfix.special/deprecatedSymbolUsage"

    fun testMemberInCompiledClass() {
        doTest("matches(input)")
    }

    fun testDefaultParameterValuesFromLibrary() {
        doTest("""prefix + joinTo(StringBuilder(), separator, "", postfix, limit, truncated, transform)""")
    }

    private fun doTest(pattern: String) {
        val testPath = KotlinTestUtils.navigationMetadata(TEST_DATA_DIR + "/" + getTestName(true) + ".kt")
        myFixture.configureByFile(testPath)

        val offset = editor.caretModel.offset
        val element = file.findElementAt(offset)
        val nameExpression = element!!.parents.firstIsInstance<KtSimpleNameExpression>()
        project.executeWriteCommand("") {
            DeprecatedSymbolUsageFix(nameExpression, ReplaceWith(pattern, emptyList())).invoke(project, editor, file)
        }

        myFixture.checkResultByFile("$testPath.after")
    }
}
