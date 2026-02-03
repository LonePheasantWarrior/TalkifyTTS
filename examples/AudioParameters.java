//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package com.alibaba.dashscope.aigc.multimodalconversation;

import com.google.gson.annotations.SerializedName;
import java.io.Serializable;

public class AudioParameters implements Serializable {
    @SerializedName("voice")
    private Voice voice;

    protected AudioParameters(AudioParametersBuilder<?, ?> b) {
        this.voice = b.voice;
    }

    public static AudioParametersBuilder<?, ?> builder() {
        return new AudioParametersBuilderImpl();
    }

    public Voice getVoice() {
        return this.voice;
    }

    public void setVoice(Voice voice) {
        this.voice = voice;
    }

    public boolean equals(Object o) {
        if (o == this) {
            return true;
        } else if (!(o instanceof AudioParameters)) {
            return false;
        } else {
            AudioParameters other = (AudioParameters)o;
            if (!other.canEqual(this)) {
                return false;
            } else {
                Object this$voice = this.getVoice();
                Object other$voice = other.getVoice();
                if (this$voice == null) {
                    if (other$voice != null) {
                        return false;
                    }
                } else if (!this$voice.equals(other$voice)) {
                    return false;
                }

                return true;
            }
        }
    }

    protected boolean canEqual(Object other) {
        return other instanceof AudioParameters;
    }

    public int hashCode() {
        int PRIME = 59;
        int result = 1;
        Object $voice = this.getVoice();
        result = result * 59 + ($voice == null ? 43 : $voice.hashCode());
        return result;
    }

    public String toString() {
        return "AudioParameters(voice=" + this.getVoice() + ")";
    }

    public abstract static class AudioParametersBuilder<C extends AudioParameters, B extends AudioParametersBuilder<C, B>> {
        private Voice voice;

        public B voice(Voice voice) {
            this.voice = voice;
            return (B)this.self();
        }

        protected abstract B self();

        public abstract C build();

        public String toString() {
            return "AudioParameters.AudioParametersBuilder(voice=" + this.voice + ")";
        }
    }

    private static final class AudioParametersBuilderImpl extends AudioParametersBuilder<AudioParameters, AudioParametersBuilderImpl> {
        private AudioParametersBuilderImpl() {
        }

        protected AudioParametersBuilderImpl self() {
            return this;
        }

        public AudioParameters build() {
            return new AudioParameters(this);
        }
    }

    public static enum Voice {
        @SerializedName("Cherry")
        CHERRY("Cherry"),
        @SerializedName("Serena")
        SERENA("Serena"),
        @SerializedName("Ethan")
        ETHAN("Ethan"),
        @SerializedName("Chelsie")
        CHELSIE("Chelsie"),
        @SerializedName("Dylan")
        DYLAN("Dylan"),
        @SerializedName("Jada")
        JADA("Jada"),
        @SerializedName("Sunny")
        SUNNY("Sunny"),
        @SerializedName("Nofish")
        NOFISH("Nofish"),
        @SerializedName("Jennifer")
        JENNIFER("Jennifer"),
        @SerializedName("Li")
        LI("Li"),
        @SerializedName("Marcus")
        MARCUS("Marcus"),
        @SerializedName("Roy")
        ROY("Roy"),
        @SerializedName("Peter")
        PETER("Peter"),
        @SerializedName("Eric")
        ERIC("Eric"),
        @SerializedName("Rocky")
        ROCKY("Rocky"),
        @SerializedName("Kiki")
        KIKI("Kiki"),
        @SerializedName("Ryan")
        RYAN("Ryan"),
        @SerializedName("Katerina")
        KATERINA("Katerina"),
        @SerializedName("Elias")
        ELIAS("Elias"),
        @SerializedName("Momo")
        MOMO("Momo"),
        @SerializedName("Moon")
        MOON("Moon"),
        @SerializedName("Maia")
        MAIA("Maia"),
        @SerializedName("Kai")
        KAI("Kai"),
        @SerializedName("Bella")
        BELLA("Bella"),
        @SerializedName("Aiden")
        AIDEN("Aiden"),
        @SerializedName("Eldric Saga")
        ELDRIC_SAGA("Eldric Saga"),
        @SerializedName("Mia")
        MIA("Mia"),
        @SerializedName("Mochi")
        MOCHI("Mochi"),
        @SerializedName("Bellona")
        BELLONA("Bellona"),
        @SerializedName("Vincent")
        VINCENT("Vincent"),
        @SerializedName("Bunny")
        BUNNY("Bunny"),
        @SerializedName("Neil")
        NEIL("Neil"),
        @SerializedName("Arthur")
        ARTHUR("Arthur"),
        @SerializedName("Nini")
        NINI("Nini"),
        @SerializedName("Ebona")
        EBONA("Ebona"),
        @SerializedName("Seren")
        SEREN("Seren"),
        @SerializedName("Pip")
        PIP("Pip"),
        @SerializedName("Stella")
        STELLA("Stella"),
        @SerializedName("Bodega")
        BODEGA("Bodega"),
        @SerializedName("Sonrisa")
        SONRISA("Sonrisa"),
        @SerializedName("Alek")
        ALEK("Alek"),
        @SerializedName("Dolce")
        DOLCE("Dolce"),
        @SerializedName("Sohee")
        SOHEE("Sohee"),
        @SerializedName("Ono Anna")
        ONO_ANNA("Ono Anna"),
        @SerializedName("Lenn")
        LENN("Lenn"),
        @SerializedName("Emilien")
        EMILIEN("Emilien"),
        @SerializedName("Andre")
        ANDRE("Andre"),
        @SerializedName("Radio Gol")
        RADIO_GOL("Radio Gol"),
        @SerializedName("Vivian")
        VIVIAN("Vivian");

        private final String value;

        private Voice(String value) {
            this.value = value;
        }

        public String getValue() {
            return this.value;
        }
    }
}
