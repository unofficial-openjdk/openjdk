module simp {
    requires java.desktop;
    provides javax.imageio.spi.ImageReaderSpi with simp.SIMPImageReaderSpi;
}
