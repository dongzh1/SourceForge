package com.dongzh1.sourceforge.multiblock

import com.esotericsoftware.kryo.kryo5.Kryo
import com.esotericsoftware.kryo.kryo5.io.Input
import com.esotericsoftware.kryo.kryo5.io.Output
import com.esotericsoftware.kryo.kryo5.serializers.CompatibleFieldSerializer
import com.esotericsoftware.kryo.kryo5.util.Pool
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32

/**
 * 源质锻炉作业的按世界持久化。镜像 GeneMachinery 的 KryoMachineSerializer + MachineFileIo：
 * - Kryo Pool（Kryo 非线程安全）+ CompatibleFieldSerializer（字段前后兼容）+ 不强制注册
 * - 文件 forge_jobs/<world>.dat，16 字节头："SFFJ" 魔数 + 版本 + serializerId + pad + CRC32，再接载荷
 * - 原子写：写到 .tmp 子目录唯一临时文件，再 Files.move(ATOMIC_MOVE, REPLACE_EXISTING)
 * - 主文件损坏/CRC 不匹配时回退 .dat.bak
 */
class ForgeJobStore(private val dir: File) {
    init {
        dir.mkdirs()
        File(dir, ".tmp").mkdirs()
    }

    private val serializerId: Byte = 1
    private val magic = byteArrayOf(0x53, 0x46, 0x46, 0x4A) // "SFFJ"
    private val headerSize = 16

    private val pool = object : Pool<Kryo>(true, false, 8) {
        override fun create(): Kryo = Kryo().apply {
            isRegistrationRequired = false
            references = true
            setDefaultSerializer(CompatibleFieldSerializer::class.java)
            register(WorldForgeJobs::class.java)
            register(ForgeJob::class.java)
            register(HashMap::class.java)
            register(ArrayList::class.java)
            classLoader = this@ForgeJobStore.javaClass.classLoader
        }
    }

    private fun sanitize(world: String): String = world.replace(Regex("[^A-Za-z0-9._-]"), "_")
    private fun fileOf(world: String) = File(dir, sanitize(world) + ".dat")
    private fun backupOf(world: String) = File(dir, sanitize(world) + ".dat.bak")

    private fun encode(data: WorldForgeJobs): ByteArray {
        val kryo = pool.obtain()
        try {
            val out = Output(4096, -1)
            kryo.writeObject(out, data)
            out.flush()
            return out.toBytes()
        } finally {
            pool.free(kryo)
        }
    }

    private fun decodePayload(bytes: ByteArray): WorldForgeJobs {
        val kryo = pool.obtain()
        try {
            Input(bytes).use { return kryo.readObject(it, WorldForgeJobs::class.java) }
        } finally {
            pool.free(kryo)
        }
    }

    fun load(world: String): WorldForgeJobs {
        val f = fileOf(world)
        if (!f.exists()) return WorldForgeJobs()
        return runCatching { decode(f.readBytes()) }.getOrElse {
            val bak = backupOf(world)
            if (bak.exists()) {
                runCatching { decode(bak.readBytes()) }.getOrDefault(WorldForgeJobs())
            } else {
                WorldForgeJobs()
            }
        }
    }

    fun save(world: String, data: WorldForgeJobs) {
        val payload = encode(data)
        val crc = CRC32().apply { update(payload) }.value
        val buf = ByteBuffer.allocate(headerSize + payload.size)
        buf.put(magic)
        buf.putShort(WorldForgeJobs.CURRENT_VERSION.toShort())
        buf.put(serializerId)
        buf.put(0)
        buf.putLong(crc)
        buf.put(payload)

        val target = fileOf(world)
        if (target.exists()) runCatching { target.copyTo(backupOf(world), overwrite = true) }
        val tmp = File(File(dir, ".tmp"), sanitize(world) + ".dat." + System.nanoTime() + ".tmp")
        tmp.writeBytes(buf.array())
        runCatching {
            Files.move(
                tmp.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
        }.onFailure {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun decode(bytes: ByteArray): WorldForgeJobs {
        require(bytes.size >= headerSize) { "file too short" }
        val buf = ByteBuffer.wrap(bytes)
        val m = ByteArray(4); buf.get(m)
        require(m.contentEquals(magic)) { "bad magic" }
        buf.short; buf.get(); buf.get() // skip version, serializerId, pad
        val crc = buf.long
        val payload = ByteArray(bytes.size - headerSize); buf.get(payload)
        val actual = CRC32().apply { update(payload) }.value
        require(actual == crc) { "crc mismatch" }
        return decodePayload(payload)
    }
}
