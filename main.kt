import java.io.Closeable
import java.io.File
import kotlin.math.floor
import kotlin.system.exitProcess

fun Int.inSection(sectionInfo: SectionInfo): Int = (this - sectionInfo.address + sectionInfo.offset).toInt()
fun List<Byte>.toInt(): Int = if (size > 4) throw RuntimeException("Too long") else
    mapIndexed { i, it -> (if (it >= 0) it.toInt() else 256 + it.toInt()) shl (i * 8) }.sum()

fun main(args: Array<String>) {
    val argsMap = args.fold(Pair(emptyMap<String, List<String>>(), "")) { (map, lastKey), elem ->
        if (elem.startsWith("-"))  Pair(map + (elem to emptyList()), elem)
        else Pair(map + (lastKey to map.getOrDefault(lastKey, emptyList()) + elem), lastKey)
    }.first
    if (argsMap["-h"] != null || argsMap["--help"] != null) {
        println("Usage:\n" +
                "    solve a bomb: java -jar main.jar path/to/bomb\n" +
                "    solve a bomb and only print the result: java -jar main.jar path/to/bomb -v 0\n" +
                "    solve a bomb and write the result into a file: java -jar main.jar path/to/bomb -o path/to/psol.txt\n" +
                "    test solving bombs: java -jar main.jar --test\n" +
                "    test solving a specific bomb: java -jar main.jar --test <uid>")
    } else if (argsMap["--test"] != null) {
        if (argsMap["--test"]!!.isNotEmpty()) {
            testDefusing(argsMap["--test"]!![0])
        } else {
            val res = testDefusing()
            if (res.first == 6) {
                println("Solves everything")
            } else {
                println("Fails at ${res.first + 1} for ${res.second.joinToString()}")
            }
        }
    } else {
        if (argsMap[""] == null || argsMap[""]!!.isEmpty()) {
            println("Please specify a bomb to defuse")
            exitProcess(1)
        }
        val verbose = argsMap["-v"]?.get(0)?.toInt() ?: 2
        val bombPath = argsMap[""]!![0]
        val txtPath = if (argsMap["-o"] != null) argsMap["-o"]!![0] else null
        val bombFile = File(bombPath)
        val txtFile = if (txtPath != null) File(txtPath) else null
        val inputs = defuseBomb(bombFile, verbose)
        if (txtFile == null) {
            if (verbose >= 1) {
                println()
                println()
            }
            println(inputs.joinToString("\n"))
        } else {
            txtFile.writeText(inputs.joinToString("\n") + "\n")
        }
    }
}

fun defuseBomb(bombFile: File, verbose: Int = 2): List<String> {
    val bombFileBytes = bombFile.readBytes()
    val bombSections = readSectionInfo(bombFile)
    val bombPltSection = bombSections.find { it.name == ".plt" }!!
    val bombTextSection = bombSections.find { it.name == ".text" }!!
    val bombRodataSection = bombSections.find { it.name == ".rodata" }!!
    val bombDataSection = bombSections.find { it.name == ".data" }!!
    val bombSymbols = readSymbolTable(bombFile)
    val inputs = mutableListOf<String>()
    fun getStringAtAddr(addr: Int, section: SectionInfo = bombRodataSection): String {
        val endAddr = addr.let {
            var res = it
            while (bombFileBytes[res.inSection(section)] != 0.toByte()) res++
            res
        }
        return bombFileBytes.slice(
                addr.inSection(section)
                        until endAddr.inSection(section))
                .joinToString("") { it.toInt().toChar().toString() }
    }

    for (i in (1..6)) {
        val functionSymbol = bombSymbols.find { it.name == "phase_$i" }!!
        val realAddr = functionSymbol.value.toInt().inSection(bombTextSection)
        val solution = try {
            when (i) {
                1 -> {
                    val phase1FunAddr = functionSymbol.value.toInt()
                    val phase1Bytes = bombFileBytes.slice(
                            phase1FunAddr.inSection(bombTextSection) until
                                    (phase1FunAddr + 9).inSection(bombTextSection))
                    if (phase1Bytes.slice(0 until 5) != listOf(
                                    0x48.toByte(), 0x83.toByte(),
                                    0xEC.toByte(), 0x08.toByte(),
                                    0xBE.toByte())) throw RuntimeException("Wrong phase_1 function header: $phase1Bytes")
                    val phase1StringAddress = phase1Bytes.slice(5 until 9).toInt()
                    val phase1String = getStringAtAddr(phase1StringAddress)
                    if (verbose >= 1) println("Found string for phase 1: '$phase1String'")
                    phase1String
                }
                2 -> {
                    val str = when (bombFileBytes[realAddr + 18]) {
                        0x79.toByte() -> {
                            "1 2 4 7 11 16" // nums[i] == nums[i-1] + i
                        }
                        0x75.toByte() -> {
                            "0 1 1 2 3 5" // nums[i] == nums[i-1] + nums[i-2], nums[0] == 0, nums[1] == 1
                        }
                        0x74.toByte() -> {
                            "1 2 4 8 16 32" // nums[i] == nums[i-1] * 2, nums[0] == 1
                        }
                        else -> throw RuntimeException("Unknown function for phase 2")
                    }
                    if (verbose >= 1) println("Found numbers for phase 2: $str")
                    str
                }
                3 -> {
                    val switchJmpAddr = (realAddr until (realAddr + functionSymbol.size - 7)).find {
                        bombFileBytes[it] == 0xFF.toByte() && bombFileBytes[it + 1] == 0x24.toByte() && bombFileBytes[it + 2] == 0xC5.toByte()
                    } ?: throw RuntimeException("Could not find the switch jump")
                    val switchTableAddr = bombFileBytes.slice((switchJmpAddr + 3) until (switchJmpAddr + 7)).toInt().inSection(bombRodataSection)
                    val firstNum = floor(Math.random() * 6).toInt()
                    val lblAddr = bombFileBytes.slice((switchTableAddr + firstNum * 8) until (switchTableAddr + firstNum * 8 + 4))
                            .toInt().inSection(bombTextSection)
                    if (bombFileBytes[realAddr + 4] == 0x4C.toByte()) {
                        if (bombFileBytes[lblAddr] != 0xB8.toByte() ||
                                (bombFileBytes[lblAddr + 5] != 0x83.toByte() && bombFileBytes[lblAddr + 5] != 0x81.toByte()) ||
                                (bombFileBytes.slice((lblAddr + 6) until (lblAddr + 9)) !=
                                                listOf(0x7C.toByte(), 0x24.toByte(), 0x08.toByte()))) throw RuntimeException("Bad switch label contents")
                        val charCode = bombFileBytes.slice((lblAddr + 1) until (lblAddr + 5)).toInt()
                        val lastNum = if (bombFileBytes[lblAddr + 5] == 0x81.toByte()) {
                            bombFileBytes.slice((lblAddr + 9) until (lblAddr + 13)).toInt()
                        } else {
                            bombFileBytes[lblAddr + 9].toInt()
                        }
                        val str = "$firstNum ${charCode.toChar()} $lastNum"
                        if (verbose >= 1) println("Found numbers for phase 3: $str")
                        str
                    } else if (bombFileBytes[realAddr + 4] == 0x48.toByte()) {
                        if (bombFileBytes[lblAddr] != 0xB8.toByte()) throw RuntimeException("Bad switch label contents")
                        var num = bombFileBytes.slice((lblAddr + 1) until (lblAddr + 5)).toInt()
                        var currentAddress = lblAddr + 5
                        while (true) {
                            if (bombFileBytes[currentAddress] == 0xEB.toByte()) {
                                currentAddress += 2 + bombFileBytes[currentAddress + 1].toInt()
                            }
                            if (bombFileBytes[currentAddress] == 0x83.toByte() && bombFileBytes[currentAddress+1] == 0xC0.toByte()) {
                                num += bombFileBytes[currentAddress+2].toInt()
                                currentAddress += 3
                            } else if (bombFileBytes[currentAddress] == 0x05.toByte()) {
                                num += bombFileBytes.slice((currentAddress + 1) until (currentAddress + 5)).toInt()
                                currentAddress += 5
                            } else if (bombFileBytes[currentAddress] == 0x83.toByte() && bombFileBytes[currentAddress+1] == 0xE8.toByte()) {
                                num -= bombFileBytes[currentAddress+2].toInt()
                                currentAddress += 3
                            } else if (bombFileBytes[currentAddress] == 0x2D.toByte()) {
                                num -= bombFileBytes.slice((currentAddress + 1) until (currentAddress + 5)).toInt()
                                currentAddress += 5
                            } else break
                        }
                        val str = "$firstNum $num"
                        if (verbose >= 1) println("Found numbers for phase 3: $str")
                        str
                    } else throw RuntimeException("Unknown phase 3")
                }
                4 -> {
                    fun func4Variant1(edi: Int, esi: Int, edx: Int): Int {
                        var eax = edx - esi
                        eax += eax ushr 31
                        eax = eax shr 1
                        val ebx = eax + esi
                        eax = if (ebx > edi) {
                            func4Variant1(edi, esi, ebx - 1) + ebx
                        } else if (ebx < edi) {
                            func4Variant1(edi, ebx + 1, edx) + ebx
                        } else {
                            ebx
                        }
                        return eax
                    }
                    fun func4Variant2(edi: Int, esi: Int, edx: Int): Int {
                        var eax = edx - esi
                        eax += eax ushr 31
                        eax = eax shr 1
                        val ebx = eax + esi
                        eax = if (ebx > edi) {
                            func4Variant2(edi, esi, ebx - 1) * 2
                        } else if (ebx < edi) {
                            func4Variant2(edi, ebx + 1, edx) * 2 + 1
                        } else {
                            0
                        }
                        return eax
                    }
                    fun func4Variant3(edi: Int, esi: Int): Int {
                        if (edi <= 0) return 0
                        if (edi == 1) return esi
                        return func4Variant3(edi-2, esi) + func4Variant3(edi-1, esi) + esi
                    }
                    when (bombFileBytes[realAddr + 8]) {
                         0x08.toByte() -> {
                            val cmpAddr = (realAddr until (realAddr + functionSymbol.size - 5)).find {
                                bombFileBytes[it] == 0x83.toByte() &&
                                        bombFileBytes.slice((it + 1) until (it + 4)) ==
                                        listOf(0x7C.toByte(), 0x24.toByte(), 0x08.toByte())
                            }!!
                            val targetVal = bombFileBytes[cmpAddr + 4].toInt()
                            val instr = bombFileBytes[bombSymbols.find { it.name == "func4" }!!.value.toInt().inSection(bombTextSection)]
                            val func4 = if (instr == 0x53.toByte()) {
                                ::func4Variant1
                            } else if (instr == 0x48.toByte()) {
                                ::func4Variant2
                            } else throw RuntimeException("Unknown func4")
                            val funArg = (0..14).find { func4(it, 0, 14) == targetVal }!!
                            val str = "$funArg $targetVal"
                            if (verbose >= 1) println("Found numbers for phase 4: $str")
                            str
                        }
                        0x0C.toByte() -> {
                            val movInstr = realAddr + 0x37
                            if (bombFileBytes[movInstr] != 0xBF.toByte()) throw RuntimeException("Bad phase 4")
                            val firstArg = bombFileBytes.slice((movInstr + 1) until (movInstr + 5)).toInt()
                            val lastNum = floor(Math.random() * 3).toInt() + 2
                            val firstNum = func4Variant3(firstArg, lastNum)
                            val str = "$firstNum $lastNum"
                            if (verbose >= 1) println("Found numbers for phase 4: $str")
                            str
                        }
                        else -> throw RuntimeException("Unknown phase 4")
                    }
                }
                5 -> {
                    when (bombFileBytes[realAddr]) {
                        0x53.toByte() -> when (bombFileBytes[realAddr+2]) {
                            0x89.toByte() -> {
                                val cmpInst = realAddr + 0x35
                                if (bombFileBytes[cmpInst] != 0x83.toByte() || bombFileBytes[cmpInst + 1] != 0xFA.toByte()) throw RuntimeException("Bad phase 5")
                                val targetSum = bombFileBytes[cmpInst + 2].toInt()
                                val addInstr = realAddr + 0x24
                                if (bombFileBytes[addInstr] != 0x03.toByte() || bombFileBytes[addInstr + 1] != 0x14.toByte()
                                        || bombFileBytes[addInstr + 2] != 0x8D.toByte()) throw RuntimeException("Bad phase 5")
                                val arrayAddr = bombFileBytes.slice((addInstr + 3) until (addInstr + 7)).toInt().inSection(bombRodataSection)
                                val array = (arrayAddr until (arrayAddr + 16 * 4) step 4).map { bombFileBytes.slice(it until (it + 4)).toInt() }

                                fun subsetSum(sum: Int, elemCount: Int, array: List<Int>, offset: Int = 0): List<Int>? {
                                    return when {
                                        sum == 0 && elemCount == 0 -> emptyList()
                                        elemCount > array.size -> null
                                        array.isEmpty() -> null
                                        array.size == 1 -> if (sum == array[0] && elemCount == 1) listOf(offset) else null
                                        else -> subsetSum(sum, elemCount, array.subList(1, array.size), offset + 1)
                                                ?: subsetSum(sum - array[0], elemCount - 1, array.subList(1, array.size), offset + 1)
                                                        ?.let { listOf(offset, *it.toTypedArray()) }
                                    }
                                }

                                val str = subsetSum(targetSum, 6, array)!!.joinToString("") { (it + 0x40).toChar().toString() }
                                if (verbose >= 1) println("Found string for phase 5: $str")
                                str
                            }
                            0x83.toByte() -> {
                                val movInstr = (realAddr until (realAddr + functionSymbol.size - 7)).find {
                                    bombFileBytes[it] == 0x0F.toByte() && bombFileBytes[it + 1] == 0xB6.toByte()
                                            && bombFileBytes[it + 2] == 0x92.toByte()
                                }!!
                                val arrayAddr = bombFileBytes.slice((movInstr + 3) until (movInstr + 7)).toInt().inSection(bombRodataSection)
                                val array = (arrayAddr until (arrayAddr + 16)).map { bombFileBytes[it].toInt().toChar() }
                                val movEsiInstr = (realAddr until (realAddr + functionSymbol.size - 10)).find {
                                    bombFileBytes.slice(it until (it + 5)) == listOf(0xC6.toByte(), 0x44.toByte(), 0x24.toByte(), 0x06.toByte(), 0x00.toByte())
                                }!! + 5
                                if (bombFileBytes[movEsiInstr] != 0xBE.toByte()) throw RuntimeException("Bad phase 5")
                                val targetString = getStringAtAddr(bombFileBytes.slice((movEsiInstr + 1) until (movEsiInstr + 5)).toInt())
                                val str = targetString.map {
                                    c -> ((
                                        array.indices.find { array[it] == c }
                                        ?: throw RuntimeException("This phase cannot be solved due to a bug in the bomb." +
                                                " Please contact a teacher providing the following explanation:" +
                                                " the bomb requires me to construct a string by specifying indices into an array" +
                                                " but the array doesn't contain the letter '$c' which is necessary to construct my string '$targetString'.")
                                    ) + 0x40).toChar().toString()
                                }.joinToString("")
                                if (verbose >= 1) println("Found string for phase 5: $str")
                                str
                            }
                            else -> throw RuntimeException("Unknown phase 5")
                        }
                        0x48.toByte() -> {
                            val movInstr = realAddr + 0x46
                            if (bombFileBytes[movInstr] != 0x8B.toByte() || bombFileBytes[movInstr + 1] != 0x04.toByte()
                                    || bombFileBytes[movInstr + 2] != 0x85.toByte()) throw RuntimeException("Bad phase 5")
                            val arrayAddr = bombFileBytes.slice((movInstr + 3) until (movInstr + 7)).toInt().inSection(bombRodataSection)
                            val array = (arrayAddr until (arrayAddr + 16 * 4) step 4).map { bombFileBytes.slice(it until (it + 4)).toInt() }
                            var idx = 15
                            var sum = 0
                            for (i in 1..15) {
                                sum += idx
                                idx = array.indices.find { array[it] == idx }!!
                            }
                            val str = "$idx $sum"
                            if (verbose >= 1) println("Found numbers for phase 5: $str")
                            str
                        }
                        else -> throw RuntimeException("Unknown phase 5")
                    }
                }
                6 -> {
                    val node1Addr = bombSymbols.find { it.name == "node1" }!!.value.toInt().inSection(bombDataSection)
                    val nodeValues = (0..5).map {
                        val nodeAddr = node1Addr + it * 0x10
                        bombFileBytes.slice(nodeAddr until (nodeAddr + 4)).toInt()
                    }
                    val jmpAddr = (realAddr until (realAddr + functionSymbol.size - 6)).find {
                        bombFileBytes[it] == 0x8B.toByte() && bombFileBytes[it + 1] == 0x00.toByte()
                                && bombFileBytes[it + 2] == 0x39.toByte() && bombFileBytes[it + 3] == 0x03.toByte()
                    }!! + 4
                    val indices = when (bombFileBytes[jmpAddr]) {
                        0x7E.toByte() -> nodeValues.indices.sortedBy { nodeValues[it] }
                        0x7D.toByte() -> nodeValues.indices.sortedByDescending { nodeValues[it] }
                        else -> throw RuntimeException("Bad phase 6")
                    }

                    val str = when (bombFileBytes[realAddr + 1]) {
                        0x56.toByte() -> indices.joinToString(" ") { (6 - it).toString() }
                        0x55.toByte() -> indices.joinToString(" ") { (it + 1).toString() }
                        else -> throw RuntimeException("Bad phase 6")
                    }
                    if (verbose >= 1) println("Found numbers for phase 6: $str")
                    str
                }
                else -> {
                    throw RuntimeException("Unknown phase $i")
                }
            }
        } catch (e: Exception) {
            if (verbose >= 1) println("Could not solve phase $i due to the following exception:")
            if (verbose >= 1) e.printStackTrace(System.out)
            null
        }

        if (solution != null && inputs.size == i-1) {
            if (verbose >= 1) print("Checking...")
            if (tryInputs(bombFile, listOf(*inputs.toTypedArray(), solution))) {
                if (verbose >= 1) println("  success!")
                inputs.add(solution)
            } else {
                if (verbose >= 1) println("  failed")
            }
        } else if (solution != null) {
            if (verbose >= 1) println("One of the previous checks failed, possible solution: $solution")
        } else {
            if (verbose >= 1) println("Could not find a solution")
        }
        if (i != 6 && verbose >= 1) {
            println()
            println()
        }
    }
    return inputs
}

fun tryInputs(bombFile: File, input: List<String>): Boolean {
    val tmp = File.createTempFile("psol", ".txt")
    tmp.writeText(input.joinToString("\n") + "\n")
    val bombProcess = ProcessBuilder(bombFile.absolutePath, tmp.absolutePath)
            .apply { environment()["GRADE_BOMB"] = "yee" }.start()
    val output = bombProcess.inputStream.bufferedReader()

    if (output.readLine() != "Welcome to my fiendish little bomb. You have 6 phases with") {
        throw RuntimeException("Not a bomb?")
    }
    if (output.readLine() != "which to blow yourself up. Have a nice day!") {
        throw RuntimeException("Not a bomb?")
    }
    var line: String? = output.readLine()
    var res = true
    while (line != null) {
        if (line == "BOOM!!!") {
            res = false
            break
        }
        line = output.readLine()
    }
    tmp.delete()
    bombProcess.destroy()
    return res
}

data class SymbolTableEntry(
        val num: Int, val value: Long, val size: Int, val type: String,
        val bind: String, val vis: String, val ndx: String, val name: String
)

fun readSymbolTable(file: File): List<SymbolTableEntry> {
    val process = ProcessBuilder("readelf", "-s", file.absolutePath).start()
    val output = process.inputStream.bufferedReader()
    val regex = Regex("\\s*(\\d+):\\s*([0-9a-fA-F]+)\\s+(\\d+)\\s+([^\\s]+)\\s+([^\\s]+)\\s+([^\\s]+)\\s+([^\\s]+)\\s+(.*)")
    val result = mutableListOf<SymbolTableEntry>()
    var line: String? = output.readLine()
    while (line != null) {
        val match = regex.matchEntire(line)
        if (match != null) {
            result.add(SymbolTableEntry(
                    match.groupValues[1].toInt(),
                    match.groupValues[2].toLong(16),
                    match.groupValues[3].toInt(),
                    match.groupValues[4], match.groupValues[5],
                    match.groupValues[6], match.groupValues[7],
                    match.groupValues[8]
            ))
        }
        line = output.readLine()
    }
    return result
}

data class SectionInfo(
        val num: Int, val name: String, val type: String, val address: Long, val offset: Long
)

fun readSectionInfo(file: File): List<SectionInfo> {
    val process = ProcessBuilder("readelf", "-S", file.absolutePath).start()
    val output = process.inputStream.bufferedReader()
    val regex = Regex("\\s*\\[\\s*(\\d+)\\s*\\]\\s*([^\\s]+)\\s+([^\\s]+)\\s+([0-9a-fA-F]+)\\s+([0-9a-fA-F]+)")
    val result = mutableListOf<SectionInfo>()
    var line: String? = output.readLine()
    while (line != null) {
        val match = regex.matchEntire(line)
        if (match != null) {
            result.add(SectionInfo(
                    match.groupValues[1].toInt(),
                    match.groupValues[2],
                    match.groupValues[3],
                    match.groupValues[4].toLong(16),
                    match.groupValues[5].toLong(16)
            ))
        }
        line = output.readLine()
    }
    return result
}

fun testDefusing(): Pair<Int, List<String>> {
    val usernames = arrayOf("vrushank.agrawal", "antoine.babu", "elsa.bismuth", "ekaterina.borisova.2023", "pedro.cabral", "youssef.chaabouni", "alejandro.christlieb", "pranshu.dave", "mark.daychman", "arthur.failler", "laura.galindo", "jean-sebastien.gaultier", "matea.gjika", "evdokia.gneusheva", "mina.goranovic", "philippe.guyard", "minjoo.kim", "dimitri.korkotashvili", "lasha.koroshinadze", "amine.lamouchi", "hieu.le", "john.levy", "zhihui.li", "yufei.liu", "virgile.martin", "kevin.messali", "antonina.mijatovic", "alexandre.misrahi", "mamoune.mouchtaki", "elena.mrdja", "milena.nedeljkovic", "alexandra-catalina.negoita", "doan-dai.nguyen", "tung.nguyen", "nykyta.ostapliuk", "cyrus.pellet", "rojin.radmehr", "clara.schneuwly", "yi-yao.tan", "hassiba.tej", "darya.todoskova", "vilius.tubinas", "johanna.ulin", "tim.valencony", "stefan.vayl", "nhat.vo", "thang-long.vu", "salma.zainana")
    var minPhase = 6
    var minUsernames = mutableListOf<String>()
    for (username in usernames) {
        try {
            BombAccessor(username).use {
                val bombFile = it.file
                val inputs = defuseBomb(bombFile, 0)
                if (inputs.size < minPhase) {
                    minPhase = inputs.size
                    minUsernames = mutableListOf(username)
                } else if (inputs.size == minPhase) {
                    minUsernames.add(username)
                }
                println("$username: ${inputs.size}, min $minPhase")
            }
        } catch (e: Exception) {
            println("Couldnt check $username: ${e.message}")
        }
    }
    return Pair(minPhase, minUsernames)
}

fun testDefusing(username: String) {
    BombAccessor(username).use {
        val bombFile = it.file
        val inputs = defuseBomb(bombFile, 2)
        println(inputs.joinToString("\n"))
    }
}

class BombAccessor(val username: String): Closeable {
    private var _file: File?
    private val tar: File
    private val unpacked: File
    init {
        val folder = File("randomness")
        if (!folder.exists()) folder.mkdir()
        tar = File(folder.absolutePath + "/$username.tar")
        val wget = ProcessBuilder("wget",
                "https://www.enseignement.polytechnique.fr/informatique/CSE205/TD/bomblab/student_bombs/$username.tar",
                "-O", tar.absolutePath
        ).start()
        if (wget.waitFor() != 0 || !tar.exists()) throw RuntimeException("Failed to wget")
        unpacked = File(folder.absolutePath + "/$username")
        val untar = ProcessBuilder(
                "tar", "xf", tar.absolutePath,
                "-C", folder.absolutePath
        ).start()
        if (untar.waitFor() != 0 || !unpacked.exists()) throw RuntimeException("Failed to extract")
        _file = unpacked.listFiles()!![0].listFiles { _: File, name: String -> name == "bomb" }!![0]
    }
    val file: File
        get() = _file ?: throw RuntimeException("Accessor already closed")

    override fun close() {
        tar.delete()
        unpacked.deleteRecursively()
        _file = null
    }
}
