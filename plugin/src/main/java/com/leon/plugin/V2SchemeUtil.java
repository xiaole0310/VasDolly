package com.leon.plugin;

import com.leon.plugin.verifier.ApkSignatureSchemeV2Verifier;
import com.leon.plugin.verifier.ZipUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Created by leontli on 17/1/18.
 */

public class V2SchemeUtil {

    /**
     * find all Id-Value Pair from ApkV2SchemeBlock
     *
     * @param apkV2SchemeBlock
     * @return
     * @throws ApkSignatureSchemeV2Verifier.SignatureNotFoundException
     */
    public static Map<Integer, ByteBuffer> getAllIdValue(ByteBuffer apkV2SchemeBlock) throws ApkSignatureSchemeV2Verifier.SignatureNotFoundException {
        ApkSignatureSchemeV2Verifier.checkByteOrderLittleEndian(apkV2SchemeBlock);
        // FORMAT:
        // OFFSET       DATA TYPE  DESCRIPTION
        // * @+0  bytes uint64:    size in bytes (excluding this field)
        // * @+8  bytes pairs
        // * @-24 bytes uint64:    size in bytes (same as the one above)
        // * @-16 bytes uint128:   magic
        ByteBuffer pairs = ApkSignatureSchemeV2Verifier.sliceFromTo(apkV2SchemeBlock, 8, apkV2SchemeBlock.capacity() - 24);
        Map<Integer, ByteBuffer> idValues = new LinkedHashMap<Integer, ByteBuffer>(); // keep order
        int entryCount = 0;
        while (pairs.hasRemaining()) {
            entryCount++;
            if (pairs.remaining() < 8) {
                throw new ApkSignatureSchemeV2Verifier.SignatureNotFoundException(
                        "Insufficient data to read size of APK Signing Block entry #" + entryCount);
            }
            long lenLong = pairs.getLong();
            if ((lenLong < 4) || (lenLong > Integer.MAX_VALUE)) {
                throw new ApkSignatureSchemeV2Verifier.SignatureNotFoundException(
                        "APK Signing Block entry #" + entryCount
                                + " size out of range: " + lenLong);
            }
            int len = (int) lenLong;
            int nextEntryPos = pairs.position() + len;
            if (len > pairs.remaining()) {
                throw new ApkSignatureSchemeV2Verifier.SignatureNotFoundException(
                        "APK Signing Block entry #" + entryCount + " size out of range: " + len
                                + ", available: " + pairs.remaining());
            }
            int id = pairs.getInt();
            idValues.put(id, ApkSignatureSchemeV2Verifier.getByteBuffer(pairs, len - 4));//4 is length of id
            if (id == ApkSignatureSchemeV2Verifier.APK_SIGNATURE_SCHEME_V2_BLOCK_ID) {
                System.out.println("find V2 signature block Id : " + ApkSignatureSchemeV2Verifier.APK_SIGNATURE_SCHEME_V2_BLOCK_ID);
            }
            pairs.position(nextEntryPos);
        }

        if (idValues.isEmpty()) {
            throw new ApkSignatureSchemeV2Verifier.SignatureNotFoundException(
                    "not have Id-Value Pair in APK Signing Block entry #" + entryCount);
        }

        return idValues;
    }

    /**
     * get apk V2 signature block from apk
     *
     * @param channelFile
     * @return
     * @throws IOException
     * @throws ApkSignatureSchemeV2Verifier.SignatureNotFoundException
     */
    public static ByteBuffer getApkV2SigningBlock(File channelFile) throws IOException, ApkSignatureSchemeV2Verifier.SignatureNotFoundException {
        if (channelFile == null || !channelFile.exists() || !channelFile.isFile()) {
            return null;
        }
        RandomAccessFile apk = new RandomAccessFile(channelFile, "r");
        //1.find the EOCD
        Pair<ByteBuffer, Long> eocdAndOffsetInFile = ApkSignatureSchemeV2Verifier.getEocd(apk);
        ByteBuffer eocd = eocdAndOffsetInFile.getFirst();
        long eocdOffset = eocdAndOffsetInFile.getSecond();

        if (ZipUtils.isZip64EndOfCentralDirectoryLocatorPresent(apk, eocdOffset)) {
            throw new ApkSignatureSchemeV2Verifier.SignatureNotFoundException("ZIP64 APK not supported");
        }

        //2.find the APK Signing Block. The block immediately precedes the Central Directory.
        long centralDirOffset = ApkSignatureSchemeV2Verifier.getCentralDirOffset(eocd, eocdOffset);//通过eocd找到中央目录的偏移量
        //3. find the apk V2 signature block
        Pair<ByteBuffer, Long> apkSchemeV2Block =
                ApkSignatureSchemeV2Verifier.findApkSigningBlock(apk, centralDirOffset);//找到V2签名块的内容和偏移量

        return apkSchemeV2Block.getFirst();
    }

    /**
     * get the Apk Section info from apk which is signatured by v2
     *
     * @param baseApk
     * @return
     * @throws IOException
     * @throws ApkSignatureSchemeV2Verifier.SignatureNotFoundException not have v2 sinature
     */
    public static ApkSectionInfo getApkSectionInfo(File baseApk) throws IOException, ApkSignatureSchemeV2Verifier.SignatureNotFoundException {
        RandomAccessFile apk = new RandomAccessFile(baseApk, "r");
        //1.find the EOCD and offset
        Pair<ByteBuffer, Long> eocdAndOffsetInFile = ApkSignatureSchemeV2Verifier.getEocd(apk);
        ByteBuffer eocd = eocdAndOffsetInFile.getFirst();
        long eocdOffset = eocdAndOffsetInFile.getSecond();

        if (ZipUtils.isZip64EndOfCentralDirectoryLocatorPresent(apk, eocdOffset)) {
            throw new ApkSignatureSchemeV2Verifier.SignatureNotFoundException("ZIP64 APK not supported");
        }

        //2.find the APK Signing Block. The block immediately precedes the Central Directory.
        long centralDirOffset = ApkSignatureSchemeV2Verifier.getCentralDirOffset(eocd, eocdOffset);//通过eocd找到中央目录的偏移量
        Pair<ByteBuffer, Long> apkSchemeV2Block =
                ApkSignatureSchemeV2Verifier.findApkSigningBlock(apk, centralDirOffset);//找到V2签名块的内容和偏移量

        //3.find the centralDir
        Pair<ByteBuffer, Long> centralDir = findCentralDir(apk, centralDirOffset, (int) (eocdOffset - centralDirOffset));
        //4.find the contentEntry
        Pair<ByteBuffer, Long> contentEntry = findContentEntry(apk, (int) apkSchemeV2Block.getSecond().longValue());

        ApkSectionInfo apkSectionInfo = new ApkSectionInfo();
        apkSectionInfo.mContentEntry = contentEntry;
        apkSectionInfo.mSchemeV2Block = apkSchemeV2Block;
        apkSectionInfo.mCentralDir = centralDir;
        apkSectionInfo.mEocd = eocdAndOffsetInFile;

        System.out.println("baseApk : " + baseApk.getAbsolutePath() + " , ApkSectionInfo : " + apkSectionInfo);
        return apkSectionInfo;
    }

    /**
     * get the CentralDir of apk
     *
     * @param baseApk
     * @param centralDirOffset
     * @param length
     * @return
     * @throws IOException
     */
    public static Pair<ByteBuffer, Long> findCentralDir(RandomAccessFile baseApk, long centralDirOffset, int length) throws IOException {
        ByteBuffer byteBuffer = getByteBuffer(baseApk, centralDirOffset, length);
        return Pair.create(byteBuffer, centralDirOffset);
    }

    /**
     * get the ContentEntry of apk
     *
     * @param baseApk
     * @param length
     * @return
     * @throws IOException
     */
    public static Pair<ByteBuffer, Long> findContentEntry(RandomAccessFile baseApk, int length) throws IOException {
        ByteBuffer byteBuffer = getByteBuffer(baseApk, 0, length);
        return Pair.create(byteBuffer, 0L);
    }

    private static ByteBuffer getByteBuffer(RandomAccessFile baseApk, long offset, int length) throws IOException {
        ByteBuffer byteBuffer = ByteBuffer.allocate(length);
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
        baseApk.seek(offset);
        baseApk.readFully(byteBuffer.array(), byteBuffer.arrayOffset(), byteBuffer.capacity());
        return byteBuffer;
    }


    /**
     * generate the new ApkV2SchemeBlock
     *
     * @param idValueMap
     * @return
     */
    public static ByteBuffer generateNewApkV2SchemeBlock(Map<Integer, ByteBuffer> idValueMap) {
        if (idValueMap == null || idValueMap.isEmpty()) {
            throw new RuntimeException("getNewApkV2SchemeBlock , id value pair is empty");
        }

        // FORMAT:
        // uint64:  size (excluding this field)
        // repeated ID-value pairs:
        //     uint64:           size (excluding this field)
        //     uint32:           ID
        //     (size - 4) bytes: value
        // uint64:  size (same as the one above)
        // uint128: magic

        long length = 16 + 8;//length is size (excluding this field) , 24 = 16 byte (magic) + 8 byte (length of the v2 block excluding first 8 byte)
        for (Map.Entry<Integer, ByteBuffer> entry : idValueMap.entrySet()) {
            ByteBuffer byteBuffer = entry.getValue();
            length += 8 + 4 + (byteBuffer.remaining());
        }

        ByteBuffer newApkV2Scheme = ByteBuffer.allocate((int) (length + 8));
        newApkV2Scheme.order(ByteOrder.LITTLE_ENDIAN);
        newApkV2Scheme.putLong(length);//1.write size (excluding this field)

        for (Map.Entry<Integer, ByteBuffer> entry : idValueMap.entrySet()) {
            ByteBuffer byteBuffer = entry.getValue();
            //2.1 write length of id-value
            newApkV2Scheme.putLong(byteBuffer.remaining() + 4);//4 is length of id
            //2.2 write id
            newApkV2Scheme.putInt(entry.getKey());
            //2.3 write value
            newApkV2Scheme.put(byteBuffer.array(), byteBuffer.arrayOffset() + byteBuffer.position(), byteBuffer.remaining());
        }

        newApkV2Scheme.putLong(length);//3.write size (same as the one above)
        newApkV2Scheme.putLong(ApkSignatureSchemeV2Verifier.APK_SIG_BLOCK_MAGIC_LO);//4. write magic
        newApkV2Scheme.putLong(ApkSignatureSchemeV2Verifier.APK_SIG_BLOCK_MAGIC_HI);//4. write magic
        if (newApkV2Scheme.remaining() > 0) {
            throw new RuntimeException("generateNewApkV2SchemeBlock error");
        }
        newApkV2Scheme.flip();
        return newApkV2Scheme;
    }


    public static boolean verifyChannelApk(String apkPath) {
        return true;
//        ApkVerifier.Builder apkVerifierBuilder = new ApkVerifier.Builder(apkPath);

//        ApkVerifier apkVerifier = apkVerifierBuilder.build();
//        ApkVerifier.Result result;
//        try {
//            result = apkVerifier.verify();
//        } catch (MinSdkVersionException e) {
//            String msg = e.getMessage();
//            if (!msg.endsWith(".")) {
//                msg += '.';
//            }
//            throw new RuntimeException(
//                    "Failed to determine APK's minimum supported platform version"
//                            + ". Use --min-sdk-version to override",
//                    e);
//        }
//        boolean verified = result.isVerified();
//        try {
//            ApkSignatureSchemeV2Verifier.verify(apkPath);
//            return true;
//        } catch (ApkSignatureSchemeV2Verifier.SignatureNotFoundException e) {
//            e.printStackTrace();
//            // No APK Signature Scheme v2 signature found
//            throw new RuntimeException("No APK Signature Scheme v2 signature found");
//        } catch (Exception e) {
//            e.printStackTrace();
//            //APK Signature Scheme v2 signature was found but did not verify
//            throw new RuntimeException("Failed to collect certificates from " + apkPath
//                    + " using APK Signature Scheme v2");
//        }
    }

}
